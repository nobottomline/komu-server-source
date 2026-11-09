/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A manga whose cover comes from an HTTP source thumbnail URL. */
data class CoverSubject(
    val mangaId: Int,
    val sourceId: Long,
    val sourceBaseUrl: String?,
    val thumbnailUrl: String,
)

/** The bytes (or failure) of one GET with the source's own client. */
data class CoverFetch(
    val status: Int,
    val bytes: ByteArray? = null,
    val contentType: String? = null,
)

/** Where covers come from and where the server caches them. */
interface CoverCatalog {
    /** `null` when the manga has no HTTP-source thumbnail. */
    suspend fun subject(mangaId: Int): CoverSubject?

    /** Every cached cover file of [mangaId] (temporary and library cache). */
    fun cachedCovers(mangaId: Int): List<File>
}

/** GETs a URL with the source's client and headers (rate limits included). */
fun interface CoverHttp {
    /**
     * Throws [CoverEgressRefused] when the fetch (or a redirect) would leave
     * [allowedHosts] or reach a non-public address, another exception on
     * network failure, and returns the status otherwise.
     */
    suspend fun get(
        sourceId: Long,
        url: String,
        allowedHosts: Set<String>,
        maximumBytes: Int,
    ): CoverFetch
}

/** A cover answer: the bytes being served and the state that describes exactly those bytes. */
class ServedCover(
    val stream: InputStream,
    val contentType: String,
    val state: CoverServeState,
)

/** What a served cover is, for the app's cache (`X-Komu-Cover`). */
enum class CoverServeState(
    val header: String?,
) {
    /** Full-size covers are off or do not apply: legacy behaviour. */
    NOT_APPLICABLE(null),

    /** The source's thumbnail is final for now (no larger original). */
    THUMBNAIL("thumbnail"),

    /** The verified original. */
    ORIGINAL("original"),

    /** The thumbnail, while the original is being checked: do not persist. */
    PENDING("pending"),
}

/**
 * "Prefer full-size covers" (ADR-0084).
 *
 * A request for a cover is always answered at once from the existing cache
 * or thumbnail fetch; nothing waits for an original. When a rule names a
 * candidate original and no verdict exists for the thumbnail URL, the cover
 * is answered as [CoverServeState.PENDING] and one background verification
 * fetches the candidate with the source's client ([CoverEgress] policy),
 * compares it with the cached thumbnail ([CoverIdentity]) and records the
 * verdict. An accepted original replaces the cached cover files in place
 * (same file name, atomic rename) under the [CoverPublicationGate]; the next
 * request gets it as [CoverServeState.ORIGINAL]. Rejections are remembered
 * per URL ([CoverVerdictStore]) and every failure keeps the thumbnail.
 *
 * The state of an answer is derived from the bytes actually served (their
 * SHA-256 against the accepted original's), never from a second look at
 * the cache, so a cover replaced while a request streams it is never
 * labelled with the other version's state.
 */
class FullSizeCoverService(
    private val preferences: CoverPreferences,
    private val store: CoverVerdictStore,
    private val inspector: () -> CoverImageInspector?,
    private val http: CoverHttp,
    private val catalog: CoverCatalog,
    private val scope: CoroutineScope,
    private val publication: CoverPublicationGate = CoverPublicationGate(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sourceSpacingMillis: Long = 1_000,
    private val maximumScheduled: Int = 512,
    concurrency: Int = 2,
    /** Test seam: runs after a verification decided to publish, before it takes the publication lock. */
    private val beforePublish: suspend (mangaId: Int) -> Unit = {},
) {
    private val logger = KotlinLogging.logger {}
    private val scheduled: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val permits = Semaphore(concurrency)
    private val sourceGates = ConcurrentHashMap<Long, SourceGate>()

    private class SourceGate {
        val mutex = Mutex()
        var lastFetchMillis = 0L
    }

    val preferFullSize: Boolean get() = preferences.preferFullSize

    fun setPreferFullSize(enabled: Boolean) {
        preferences.preferFullSize = enabled
    }

    /** Cache token the app adds to cover URLs; changes when the policy does. */
    val cacheToken: String? get() = if (preferences.preferFullSize) "o${CoverOriginalRules.VERSION}" else null

    // ------------------------------------------------------------ serving

    /**
     * Classifies the cover [opened] for [mangaId] (the stream and content
     * type the cache returned) and, when a decision is still open,
     * schedules its background verification. Never blocks on the network.
     * When a rule applies, the bytes are read once and the state is derived
     * from exactly those bytes.
     */
    suspend fun serve(
        mangaId: Int,
        opened: Pair<InputStream, String>,
    ): ServedCover {
        val (stream, contentType) = opened
        // Every answer is read once and typed from its bytes, whatever the policy.
        val bytes = stream.use { it.readAllBytes() }
        val type = ImageUtil.findImageType { bytes.inputStream() }?.mime ?: contentType
        return ServedCover(bytes.inputStream(), type, stateOrFallback(mangaId, bytes))
    }

    private suspend fun stateOrFallback(
        mangaId: Int,
        bytes: ByteArray,
    ): CoverServeState {
        try {
            return serveState(mangaId, bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(error) { "Full-size cover state unavailable for manga $mangaId" }
        }
        // Not cacheable when a rule may apply: the next request decides.
        return if (preferences.preferFullSize) CoverServeState.PENDING else CoverServeState.NOT_APPLICABLE
    }

    /** The state of [servedBytes] as the cover of [mangaId]; see [serve]. */
    suspend fun serveState(
        mangaId: Int,
        servedBytes: ByteArray,
    ): CoverServeState {
        if (!preferences.preferFullSize) return CoverServeState.NOT_APPLICABLE
        val subject = catalog.subject(mangaId) ?: return CoverServeState.NOT_APPLICABLE
        CoverOriginalRules.candidateFor(subject.thumbnailUrl, subject.sourceBaseUrl) ?: return CoverServeState.THUMBNAIL
        return stateFor(mangaId, subject, servedBytes)
    }

    private fun stateFor(
        mangaId: Int,
        subject: CoverSubject,
        servedBytes: ByteArray,
    ): CoverServeState {
        val verdict = currentVerdict(subject.thumbnailUrl)
        return when {
            verdict == null -> {
                scheduleOrPending(mangaId)
            }

            verdict.kind == CoverVerdictKind.ACCEPTED -> {
                if (isOriginal(servedBytes, verdict)) CoverServeState.ORIGINAL else scheduleOrPending(mangaId)
            }

            // A due recheck comes first: a rejected or exhausted cover is
            // looked at again through ordinary traffic once its time is up,
            // and a format mismatch as soon as the cached format changed.
            isDue(verdict) || formatChanged(verdict, mangaId) -> {
                scheduleOrPending(mangaId)
            }

            verdict.kind.isFinal || verdict.isExhausted -> {
                CoverServeState.THUMBNAIL
            }

            else -> {
                CoverServeState.PENDING
            }
        }
    }

    /**
     * The verified original's bytes for [thumbnailUrl], fetched now, when a
     * verdict has already accepted it. Lets a cover cache that was cleared
     * (a refreshed title, a purged temporary cache) refill with the
     * original directly. A changed, gone or refused original drops the
     * verdict, so it is verified again; `null` means use the thumbnail.
     */
    suspend fun acceptedOriginal(
        sourceId: Long,
        sourceBaseUrl: String?,
        thumbnailUrl: String,
    ): CoverFetch? {
        if (!preferences.preferFullSize) return null
        val candidate = CoverOriginalRules.candidateFor(thumbnailUrl, sourceBaseUrl) ?: return null
        val verdict = currentVerdict(thumbnailUrl)?.takeIf { it.kind == CoverVerdictKind.ACCEPTED } ?: return null
        // Replaces the thumbnail fetch the user is waiting for, so it is not
        // paced like background checks; the source client's own rate limit applies.
        val fetched =
            try {
                http.get(sourceId, candidate.url, candidate.allowedHosts, MAXIMUM_ORIGINAL_BYTES)
            } catch (_: CoverEgressRefused) {
                store.remove(thumbnailUrl)
                return null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        val bytes = fetched?.bytes?.takeIf { fetched.status in 200..299 }
        if (bytes != null && isOriginal(bytes, verdict)) return fetched
        if (fetched != null && fetched.status in 200..499) store.remove(thumbnailUrl) // changed or gone: verify again
        return null
    }

    // ------------------------------------------------------- verification

    private fun scheduleOrPending(mangaId: Int): CoverServeState {
        schedule(mangaId)
        return CoverServeState.PENDING
    }

    /**
     * Queues one verification of [mangaId]. Duplicate requests coalesce; a
     * full queue drops the request (the cover stays pending and is queued
     * again the next time it is shown).
     */
    fun schedule(mangaId: Int): Boolean {
        if (scheduled.size >= maximumScheduled || !scheduled.add(mangaId)) return false
        scope.launch {
            try {
                permits.withPermit { verify(mangaId) }
            } catch (error: Throwable) {
                logger.warn(error) { "Full-size cover check failed for manga $mangaId" }
            } finally {
                scheduled.remove(mangaId)
            }
        }
        return true
    }

    /** A check for [mangaId] is queued or running. */
    fun isScheduled(mangaId: Int): Boolean = mangaId in scheduled

    /**
     * Verifies [mangaId]'s candidate original and publishes it when it is
     * the same picture, larger. Returns the verdict recorded (or reused).
     */
    suspend fun verify(mangaId: Int): CoverVerdict? {
        if (!preferences.preferFullSize) return null
        // Captured before anything is read: an invalidation after this point
        // (a refresh, a restore, a cleared cache) fails the publication.
        val generation = publication.generation(mangaId)
        val subject = catalog.subject(mangaId) ?: return null
        val candidate = CoverOriginalRules.candidateFor(subject.thumbnailUrl, subject.sourceBaseUrl) ?: return null
        val existing = currentVerdict(subject.thumbnailUrl)
        val cached = catalog.cachedCovers(mangaId)
        val cachedTypes = typesOf(cached)
        if (existing != null &&
            existing.kind != CoverVerdictKind.ACCEPTED &&
            !isDue(existing) &&
            !formatChanged(existing, cachedTypes)
        ) {
            return existing
        }

        if (existing?.kind == CoverVerdictKind.ACCEPTED && cached.all { isOriginal(it, existing) }) return existing
        // An original accepted before (under any rule-set version) is not a thumbnail.
        val previousOriginal = store.get(subject.thumbnailUrl)?.takeIf { it.kind == CoverVerdictKind.ACCEPTED }
        val holdsPreviousOriginal = previousOriginal != null && cached.any { isOriginal(it, previousOriginal) }

        val thumbnailHost = subject.thumbnailUrl.toHttpUrlOrNull()?.host ?: return null
        val reference =
            cached.firstOrNull { previousOriginal == null || !isOriginal(it, previousOriginal) }?.readBytes()
                ?: fetchBytes(subject.sourceId, subject.thumbnailUrl, setOf(thumbnailHost))
                ?: return record(subject, existing, CoverComparison(CoverVerdictKind.TRANSIENT))
        val fetched =
            try {
                paced(subject.sourceId) { http.get(subject.sourceId, candidate.url, candidate.allowedHosts, MAXIMUM_ORIGINAL_BYTES) }
            } catch (error: CoverEgressRefused) {
                logger.info { "Original refused for manga $mangaId (${candidate.ruleId}): ${error.message}" }
                return finish(
                    subject,
                    generation,
                    existing,
                    CoverComparison(CoverVerdictKind.REFUSED),
                    reference,
                    holdsPreviousOriginal,
                    reason = error.message,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.debug { "Original not fetched (${error.javaClass.simpleName}) for manga $mangaId" }
                return record(subject, existing, CoverComparison(CoverVerdictKind.TRANSIENT))
            }
        val statusKind = classify(fetched.status)
        val comparison =
            when {
                statusKind != null -> {
                    CoverComparison(statusKind)
                }

                fetched.bytes == null -> {
                    CoverComparison(CoverVerdictKind.NOT_IMAGE)
                }

                // An unsolved challenge or error page: retried, never a 30-day rejection.
                isHtml(fetched) -> {
                    CoverComparison(CoverVerdictKind.TRANSIENT)
                }

                // The cache keeps one name per cover and readers (2418 included)
                // type it by extension, so an original must have the cached format.
                ImageUtil.findImageType { fetched.bytes.inputStream() }?.extension.let {
                    it != null && cachedTypes.isNotEmpty() &&
                        cachedTypes != setOf(it)
                } -> {
                    CoverComparison(CoverVerdictKind.FORMAT_MISMATCH, cachedFormats = cachedTypes)
                }

                else -> {
                    compare(reference, fetched.bytes, candidate)
                }
            }
        val verdict = finish(subject, generation, existing, comparison, reference, holdsPreviousOriginal, fetched.bytes)
        logger.info {
            "Full-size cover ${verdict.kind} for manga $mangaId (${candidate.ruleId}, gain=${comparison.resolutionGain}, " +
                "hash=${comparison.hashDistance})"
        }
        return verdict
    }

    /**
     * Records the verdict and makes the cache agree with it: an accepted
     * original is published; a final rejection replaces an original that
     * an earlier verdict had published with the thumbnail again.
     */
    private suspend fun finish(
        subject: CoverSubject,
        generation: CoverPublicationGate.Generation,
        existing: CoverVerdict?,
        comparison: CoverComparison,
        reference: ByteArray,
        holdsPreviousOriginal: Boolean,
        originalBytes: ByteArray? = null,
        reason: String? = null,
    ): CoverVerdict {
        val accepted = comparison.kind == CoverVerdictKind.ACCEPTED
        val verdict = record(subject, existing, comparison, originalBytes.takeIf { accepted }, reason)
        when {
            accepted -> publish(subject, generation, originalBytes!!)
            verdict.kind.isFinal && holdsPreviousOriginal -> publish(subject, generation, reference)
        }
        return verdict
    }

    private fun compare(
        thumbnailBytes: ByteArray,
        originalBytes: ByteArray,
        candidate: CoverCandidate,
    ): CoverComparison {
        if (ImageUtil.findImageType { originalBytes.inputStream() } == null) return CoverComparison(CoverVerdictKind.NOT_IMAGE)
        val inspector = inspector() ?: return CoverComparison(CoverVerdictKind.UNVERIFIABLE)
        val original = inspector.inspect(originalBytes) ?: return CoverComparison(CoverVerdictKind.NOT_IMAGE)
        // A cached thumbnail the platform cannot decode cannot vouch for anything.
        val thumbnail = inspector.inspect(thumbnailBytes) ?: return CoverComparison(CoverVerdictKind.UNVERIFIABLE)
        return CoverIdentity.compare(thumbnail, original, candidate.maxAspectDeviation)
    }

    private fun record(
        subject: CoverSubject,
        previous: CoverVerdict?,
        comparison: CoverComparison,
        originalBytes: ByteArray? = null,
        reason: String? = null,
    ): CoverVerdict {
        // Failures that will be retried count consecutively whatever their
        // kind (a network error after an undecodable answer is the second
        // attempt, not a new first); any final outcome starts over.
        val consecutiveFailure = previous != null && !previous.kind.isFinal && !comparison.kind.isFinal
        val verdict =
            CoverVerdict(
                kind = comparison.kind,
                rulesVersion = CoverOriginalRules.VERSION,
                checkedAtEpochSeconds = nowEpochSeconds(),
                attempts = if (consecutiveFailure) previous!!.attempts + 1 else 1,
                originalSha256 = originalBytes?.let(CoverVerdictStore::sha256Hex),
                originalBytes = originalBytes?.size?.toLong(),
                reason = reason,
                cachedFormats = comparison.cachedFormats,
            )
        store.put(subject.thumbnailUrl, verdict)
        return verdict
    }

    /**
     * Replaces every cached cover file of the manga with [bytes], keeping
     * each file's name: a reader that found the name always opens one
     * complete image (the old one or the new one; an open stream keeps the
     * old file), never nothing. The bytes are staged beside the cache
     * directory and renamed over the file atomically, under the
     * publication lock and only while the manga's generation is still the
     * one the verification started from.
     */
    private suspend fun publish(
        subject: CoverSubject,
        generation: CoverPublicationGate.Generation,
        bytes: ByteArray,
    ): Boolean {
        if (!preferences.preferFullSize) return false
        // A refresh to another cover URL that has not invalidated the cache (yet).
        if (catalog.subject(subject.mangaId)?.thumbnailUrl != subject.thumbnailUrl) return false
        beforePublish(subject.mangaId)
        val published =
            publication.publishIfCurrent(subject.mangaId, generation) {
                for (existing in catalog.cachedCovers(subject.mangaId)) replace(existing, bytes, subject.mangaId)
            }
        if (!published) logger.info { "Cover of manga ${subject.mangaId} changed during verification; not published" }
        return published
    }

    private fun replace(
        target: File,
        bytes: ByteArray,
        mangaId: Int,
    ) {
        // Never a format under another format's name (verified before; re-checked here).
        if (typeOfName(target.name) != ImageUtil.findImageType { bytes.inputStream() }?.extension) return
        val directory = target.parentFile ?: return
        val staging = File(directory.parentFile ?: directory, ".komu-cover-staging").apply { mkdirs() }
        val staged = File(staging, "$mangaId.${UUID.randomUUID()}")
        try {
            staged.writeBytes(bytes)
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            logger.warn(error) { "Could not publish the cover of manga $mangaId" }
        } finally {
            staged.delete()
        }
    }

    // ------------------------------------------------------------ helpers

    private fun currentVerdict(thumbnailUrl: String): CoverVerdict? =
        store.get(thumbnailUrl)?.takeIf { it.rulesVersion == CoverOriginalRules.VERSION }

    /**
     * A format mismatch was judged against the formats cached then; a
     * refresh that cached another format (same URL) makes it stale at once
     * instead of after [CoverVerdict.REJECTION_RECHECK]. An empty cache
     * changes nothing (it refills before the next answer). A mismatch
     * recorded without its formats is re-checked once.
     */
    private fun formatChanged(
        verdict: CoverVerdict,
        cachedTypes: Set<String>,
    ): Boolean =
        verdict.kind == CoverVerdictKind.FORMAT_MISMATCH &&
            cachedTypes.isNotEmpty() &&
            verdict.cachedFormats != cachedTypes

    private fun formatChanged(
        verdict: CoverVerdict,
        mangaId: Int,
    ): Boolean = verdict.kind == CoverVerdictKind.FORMAT_MISMATCH && formatChanged(verdict, typesOf(catalog.cachedCovers(mangaId)))

    private fun typesOf(files: List<File>): Set<String> = files.mapNotNullTo(HashSet()) { typeOfName(it.name) }

    private fun isDue(verdict: CoverVerdict): Boolean = verdict.recheckAfterEpochSeconds()?.let { nowEpochSeconds() >= it } ?: false

    private fun isOriginal(
        file: File,
        verdict: CoverVerdict,
    ): Boolean {
        if (!file.isFile || file.length() != verdict.originalBytes) return false
        return runCatching { isOriginal(file.readBytes(), verdict) }.getOrDefault(false)
    }

    private fun isOriginal(
        bytes: ByteArray,
        verdict: CoverVerdict,
    ): Boolean =
        verdict.originalSha256 != null &&
            bytes.size.toLong() == verdict.originalBytes &&
            CoverVerdictStore.sha256Hex(bytes) == verdict.originalSha256

    private suspend fun fetchBytes(
        sourceId: Long,
        url: String,
        allowedHosts: Set<String>,
    ): ByteArray? =
        runCatching { paced(sourceId) { http.get(sourceId, url, allowedHosts, MAXIMUM_ORIGINAL_BYTES) } }
            .getOrNull()
            ?.takeIf { it.status in 200..299 }
            ?.bytes

    /** One fetch per source at a time, at most one per [sourceSpacingMillis]. */
    private suspend fun <T> paced(
        sourceId: Long,
        block: suspend () -> T,
    ): T {
        val gate = sourceGates.getOrPut(sourceId) { SourceGate() }
        return gate.mutex.withLock {
            val wait = gate.lastFetchMillis + sourceSpacingMillis - nowMillis()
            if (wait > 0) delay(wait)
            try {
                block()
            } finally {
                gate.lastFetchMillis = nowMillis()
            }
        }
    }

    companion object {
        /** Covers are far below this; a larger body is not an image we want. */
        const val MAXIMUM_ORIGINAL_BYTES = 12 * 1024 * 1024

        /** The image type a cache file name declares (`jpeg` is `jpg`). */
        internal fun typeOfName(name: String): String? =
            name
                .substringAfterLast('.', "")
                .lowercase()
                .let { if (it == "jpeg") "jpg" else it }
                .takeIf { it.isNotEmpty() }

        /** An HTML page (a challenge, an error page) instead of an image. */
        internal fun isHtml(fetched: CoverFetch): Boolean {
            if (fetched.contentType?.lowercase()?.startsWith("text/html") == true) return true
            val head =
                fetched.bytes
                    ?.take(64)
                    ?.toByteArray()
                    ?.toString(Charsets.ISO_8859_1)
                    ?.trimStart()
                    ?.lowercase() ?: return false
            return listOf("<!doctype html", "<html", "<head", "<!--").any { head.startsWith(it) }
        }

        /** `null` for a success; the verdict for a failed status otherwise. */
        fun classify(status: Int): CoverVerdictKind? =
            when (status) {
                in 200..299 -> null

                // 403 is how an unsolved Cloudflare challenge answers: retried with backoff.
                403, 408, 425, 429 -> CoverVerdictKind.TRANSIENT

                413 -> CoverVerdictKind.NOT_IMAGE

                // A redirect without a usable Location.
                in 300..399 -> CoverVerdictKind.REFUSED

                in 400..499 -> CoverVerdictKind.NOT_FOUND

                else -> CoverVerdictKind.TRANSIENT
            }
    }
}
