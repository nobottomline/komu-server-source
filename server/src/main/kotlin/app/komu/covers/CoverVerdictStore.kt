/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The verdict for one source thumbnail URL under one rule-set version.
 *
 * [originalSha256] and [originalBytes] identify the accepted original, so a
 * cached cover can be recognised as that original without another fetch.
 */
data class CoverVerdict(
    val kind: CoverVerdictKind,
    val rulesVersion: Int,
    val checkedAtEpochSeconds: Long,
    val attempts: Int = 1,
    val originalSha256: String? = null,
    val originalBytes: Long? = null,
) {
    /** When this verdict should be checked again; `null`: never. */
    fun recheckAfterEpochSeconds(): Long? =
        when (kind) {
            CoverVerdictKind.ACCEPTED, CoverVerdictKind.NO_RULE -> {
                null
            }

            CoverVerdictKind.TRANSIENT, CoverVerdictKind.UNVERIFIABLE -> {
                checkedAtEpochSeconds + transientBackoff(attempts).inWholeSeconds
            }

            else -> {
                checkedAtEpochSeconds + REJECTION_RECHECK.inWholeSeconds
            }
        }

    /** A transient failure that has run out of retries counts as a rejection. */
    val isExhausted: Boolean get() = !kind.isFinal && attempts >= MAXIMUM_TRANSIENT_ATTEMPTS

    companion object {
        /** A rejected original is looked for again after this long. */
        val REJECTION_RECHECK: Duration = 30.days

        const val MAXIMUM_TRANSIENT_ATTEMPTS = 4

        /** 15 min, 1 h, 4 h, then (exhausted) 16 h before the count restarts. */
        fun transientBackoff(attempts: Int): Duration = 15.minutes * (1 shl (2 * (attempts - 1).coerceIn(0, 3)))
    }
}

/**
 * Durable verdicts keyed by the SHA-256 of the thumbnail URL, one small
 * properties file each under `komu-covers/verdicts/`, written atomically.
 * A verdict is written once per cover (on verification), so per-entry files
 * avoid rewriting a large index; reads go through a bounded memory cache.
 */
class CoverVerdictStore(
    private val root: File,
    private val memoryEntries: Int = 2048,
) {
    private val memory =
        object : LinkedHashMap<String, CoverVerdict?>(memoryEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverVerdict?>?) = size > memoryEntries
        }

    fun get(thumbnailUrl: String): CoverVerdict? {
        val key = key(thumbnailUrl)
        synchronized(memory) { if (memory.containsKey(key)) return memory[key] }
        val verdict = read(file(key))
        synchronized(memory) { memory[key] = verdict }
        return verdict
    }

    fun put(
        thumbnailUrl: String,
        verdict: CoverVerdict,
    ) {
        val key = key(thumbnailUrl)
        val target = file(key)
        target.parentFile.mkdirs()
        val properties =
            Properties().apply {
                setProperty("kind", verdict.kind.name)
                setProperty("rules", verdict.rulesVersion.toString())
                setProperty("checkedAt", verdict.checkedAtEpochSeconds.toString())
                setProperty("attempts", verdict.attempts.toString())
                verdict.originalSha256?.let { setProperty("originalSha256", it) }
                verdict.originalBytes?.let { setProperty("originalBytes", it.toString()) }
            }
        val staging = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}")
        try {
            staging.outputStream().use { properties.store(it, null) }
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
        synchronized(memory) { memory[key] = verdict }
    }

    fun remove(thumbnailUrl: String) {
        val key = key(thumbnailUrl)
        file(key).delete()
        synchronized(memory) { memory[key] = null }
    }

    private fun file(key: String) = File(File(root, key.substring(0, 2)), "$key.properties")

    private fun read(file: File): CoverVerdict? {
        if (!file.isFile) return null
        return runCatching {
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            CoverVerdict(
                kind = CoverVerdictKind.valueOf(properties.getProperty("kind")),
                rulesVersion = properties.getProperty("rules").toInt(),
                checkedAtEpochSeconds = properties.getProperty("checkedAt").toLong(),
                attempts = properties.getProperty("attempts")?.toIntOrNull() ?: 1,
                originalSha256 = properties.getProperty("originalSha256"),
                originalBytes = properties.getProperty("originalBytes")?.toLongOrNull(),
            )
        }.getOrNull() // An unreadable verdict is simply re-verified.
    }

    companion object {
        fun key(thumbnailUrl: String): String = sha256Hex(thumbnailUrl.toByteArray(Charsets.UTF_8))

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
