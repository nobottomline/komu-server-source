/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import java.io.File
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

    /** The cached cover file a request for [mangaId] is answered from. */
    fun servedCover(mangaId: Int): File?

    /** Every cached cover file of [mangaId] (temporary and library cache). */
    fun cachedCovers(mangaId: Int): List<File>
}

/** GETs a URL with the source's client and headers (rate limits included). */
fun interface CoverHttp {
    /** Throws on network failure; returns the status otherwise. */
    suspend fun get(
        sourceId: Long,
        url: String,
        maximumBytes: Int,
    ): CoverFetch
}

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
 * fetches the candidate with the source's client, compares it with the
 * cached thumbnail ([CoverIdentity]) and records the verdict. An accepted
 * original replaces the cached cover files atomically; the next request
 * gets it as [CoverServeState.ORIGINAL]. Rejections are remembered per URL
 * ([CoverVerdictStore]) and every failure keeps the thumbnail.
 */
class FullSizeCoverService(
    private val preferences: CoverPreferences,
    private val store: CoverVerdictStore,
    private val inspector: () -> CoverImageInspector?,
    private val http: CoverHttp,
    private val catalog: CoverCatalog,
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sourceSpacingMillis: Long = 1_000,
    private val maximumScheduled: Int = 512,
    concurrency: Int = 2,
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
     * Classifies the cover just served for [mangaId] and, when a decision
     * is still open, schedules its background verification. Never blocks
     * on the network.
     */
    suspend fun serveState(mangaId: Int): CoverServeState {
        if (!preferences.preferFullSize) return CoverServeState.NOT_APPLICABLE
        val subject = catalog.subject(mangaId) ?: return CoverServeState.NOT_APPLICABLE
        CoverOriginalRules.candidateFor(subject.thumbnailUrl, subject.sourceBaseUrl)
            ?: return CoverServeState.THUMBNAIL
        val verdict = currentVerdict(subject.thumbnailUrl)
        return when {
            verdict == null -> scheduleOrPending(mangaId)
            verdict.kind == CoverVerdictKind.ACCEPTED ->
                if (isOriginal(catalog.servedCover(mangaId), verdict)) {
                    CoverServeState.ORIGINAL
                } else {
                    scheduleOrPending(mangaId)
                }
            verdict.kind.isFinal || verdict.isExhausted -> CoverServeState.THUMBNAIL
            isDue(verdict) -> scheduleOrPending(mangaId)
            else -> CoverServeState.PENDING
        }
    }

    /**
     * The verified original's bytes for [thumbnailUrl], fetched now, when a
     * verdict has already accepted it. Lets a cover cache that was cleared
     * (a refreshed title, a purged temporary cache) refill with the
     * original directly. A changed original drops the verdict, so it is
     * verified again; `null` means use the thumbnail.
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
        val fetched = runCatching { http.get(sourceId, candidate.url, MAXIMUM_ORIGINAL_BYTES) }.getOrNull()
        val bytes = fetched?.bytes?.takeIf { fetched.status in 200..299 }
        if (bytes != null && CoverVerdictStore.sha256Hex(bytes) == verdict.originalSha256) return fetched
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
     * Verifies [mangaId]'s candidate original and installs it when it is
     * the same picture, larger. Returns the verdict recorded (or reused).
     */
    suspend fun verify(mangaId: Int): CoverVerdict? {
        if (!preferences.preferFullSize) return null
        val subject = catalog.subject(mangaId) ?: return null
        val candidate = CoverOriginalRules.candidateFor(subject.thumbnailUrl, subject.sourceBaseUrl) ?: return null
        val existing = currentVerdict(subject.thumbnailUrl)
        if (existing != null && existing.kind != CoverVerdictKind.ACCEPTED && !isDue(existing)) return existing

        val cached = catalog.cachedCovers(mangaId)
        if (existing?.kind == CoverVerdictKind.ACCEPTED && cached.all { isOriginal(it, existing) }) return existing

        val reference =
            cached.firstOrNull { existing == null || !isOriginal(it, existing) }?.readBytes()
                ?: fetchBytes(subject.sourceId, subject.thumbnailUrl)
                ?: return record(subject, existing, CoverComparison(CoverVerdictKind.TRANSIENT))
        val fetched =
            try {
                paced(subject.sourceId) { http.get(subject.sourceId, candidate.url, MAXIMUM_ORIGINAL_BYTES) }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                logger.debug { "Original not fetched (${error.javaClass.simpleName}) for manga $mangaId" }
                return record(subject, existing, CoverComparison(CoverVerdictKind.TRANSIENT))
            }
        val statusKind = classify(fetched.status)
        if (statusKind != null) return record(subject, existing, CoverComparison(statusKind))
        val originalBytes = fetched.bytes ?: return record(subject, existing, CoverComparison(CoverVerdictKind.NOT_IMAGE))

        val comparison = compare(reference, originalBytes, candidate)
        val verdict = record(subject, existing, comparison, originalBytes.takeIf { comparison.kind == CoverVerdictKind.ACCEPTED })
        if (verdict.kind == CoverVerdictKind.ACCEPTED) install(subject, originalBytes)
        logger.info {
            "Full-size cover ${verdict.kind} for manga $mangaId (${candidate.ruleId}, gain=${comparison.resolutionGain}, " +
                "hash=${comparison.hashDistance})"
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
    ): CoverVerdict {
        val attempts =
            if (!comparison.kind.isFinal && previous != null && previous.kind == comparison.kind) previous.attempts + 1 else 1
        val verdict =
            CoverVerdict(
                kind = comparison.kind,
                rulesVersion = CoverOriginalRules.VERSION,
                checkedAtEpochSeconds = nowEpochSeconds(),
                attempts = attempts,
                originalSha256 = originalBytes?.let(CoverVerdictStore::sha256Hex),
                originalBytes = originalBytes?.size?.toLong(),
            )
        store.put(subject.thumbnailUrl, verdict)
        return verdict
    }

    /**
     * Replaces every cached cover of the manga with the original. Each file
     * is staged beside its cache directory and moved in atomically; a file
     * of another extension is removed only after the original is in place,
     * so a concurrent reader always finds one complete image.
     */
    private suspend fun install(
        subject: CoverSubject,
        bytes: ByteArray,
    ) {
        // The title may have been refreshed to another cover meanwhile.
        if (!preferences.preferFullSize || catalog.subject(subject.mangaId)?.thumbnailUrl != subject.thumbnailUrl) return
        val extension = ImageUtil.findImageType { bytes.inputStream() }?.extension ?: "jpg"
        for (existing in catalog.cachedCovers(subject.mangaId)) {
            val directory = existing.parentFile ?: continue
            val staging = File(directory.parentFile ?: directory, ".komu-cover-staging").apply { mkdirs() }
            val staged = File(staging, "${subject.mangaId}.${UUID.randomUUID()}")
            val target = File(directory, "${subject.mangaId}.$extension")
            try {
                staged.writeBytes(bytes)
                try {
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                if (existing.name != target.name) existing.delete()
            } catch (error: Exception) {
                logger.warn(error) { "Could not install the original cover of manga ${subject.mangaId}" }
            } finally {
                staged.delete()
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun currentVerdict(thumbnailUrl: String): CoverVerdict? =
        store.get(thumbnailUrl)?.takeIf { it.rulesVersion == CoverOriginalRules.VERSION }

    private fun isDue(verdict: CoverVerdict): Boolean = verdict.recheckAfterEpochSeconds()?.let { nowEpochSeconds() >= it } ?: false

    private fun isOriginal(
        file: File?,
        verdict: CoverVerdict,
    ): Boolean {
        if (file == null || !file.isFile || verdict.originalBytes == null) return false
        if (file.length() != verdict.originalBytes) return false
        return CoverVerdictStore.sha256Hex(file.readBytes()) == verdict.originalSha256
    }

    private suspend fun fetchBytes(
        sourceId: Long,
        url: String,
    ): ByteArray? =
        runCatching { paced(sourceId) { http.get(sourceId, url, MAXIMUM_ORIGINAL_BYTES) } }
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

        /** `null` for a success; the verdict for a failed status otherwise. */
        fun classify(status: Int): CoverVerdictKind? =
            when (status) {
                in 200..299 -> null
                408, 425, 429 -> CoverVerdictKind.TRANSIENT
                413 -> CoverVerdictKind.NOT_IMAGE
                in 400..499 -> CoverVerdictKind.NOT_FOUND
                else -> CoverVerdictKind.TRANSIENT
            }
    }
}
