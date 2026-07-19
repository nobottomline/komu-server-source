package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal interface ExtensionArtifactTransport {
    suspend fun download(request: Request): StagedArtifact

    data class Request(
        val url: String,
        val stagingDirectory: Path,
        val packageName: String,
        val kind: ArtifactKind,
    )
}

internal enum class ArtifactKind(
    val suffix: String,
) {
    APK("apk"),
    JAR("jar"),
}

/** Operation-owned artifact. Closing it deletes only this operation's path. */
internal class StagedArtifact internal constructor(
    val path: Path,
    val size: Long,
    val sha256: String,
    val finalUrl: String,
    val kind: ArtifactKind,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun assertIntegrity(maximumBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES) {
        require(maximumBytes in 1..DEFAULT_MAX_ARTIFACT_BYTES) { "Artifact integrity limit is invalid" }
        require(size in 1..maximumBytes) { "Staged extension artifact has an invalid recorded size" }
        require(SHA256_HEX.matches(sha256)) { "Staged extension artifact has an invalid recorded digest" }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Staged extension artifact is not a regular file" }
        require(Files.size(path) == size) { "Staged extension artifact size changed" }
        if (sha256(path, maximumBytes) != sha256) {
            throw SecurityException("Staged extension artifact digest changed")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) Files.deleteIfExists(path)
    }

    companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES = 32L * 1024L * 1024L
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        internal fun sha256(
            path: Path,
            maximumBytes: Long,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var readBytes = 0L
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    readBytes += count
                    require(readBytes <= maximumBytes) { "Staged extension artifact grew while hashing" }
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toHex()
        }

        internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
