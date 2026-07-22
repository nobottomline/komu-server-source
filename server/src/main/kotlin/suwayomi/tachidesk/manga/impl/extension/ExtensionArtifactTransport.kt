package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.net.URI
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

/** Shared fail-closed validation for transport and preflight boundaries. */
internal object ExtensionArtifactInputPolicy {
    fun requireHttpsUrl(url: String) {
        require(url.length in 1..MAX_URL_LENGTH) { "Extension artifact URL has an invalid length" }
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Invalid extension artifact URL", it) }
        require(!uri.isOpaque) { "Extension artifact URL must be hierarchical" }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Extension artifact URL must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Extension artifact URL must include a host" }
        require(uri.userInfo == null) { "Extension artifact URL must not contain credentials" }
        require(uri.fragment == null) { "Extension artifact URL must not contain a fragment" }
        require(uri.port == -1 || uri.port in 1..65535) { "Extension artifact URL has an invalid port" }
    }

    fun requirePackageName(packageName: String) {
        require(packageName.length in 1..MAX_PACKAGE_NAME_LENGTH) { "Extension package name has an invalid length" }
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "Invalid extension package name" }
    }

    private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private const val MAX_PACKAGE_NAME_LENGTH = 255
    private const val MAX_URL_LENGTH = 4096
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
        assertIntegrity(path, size, sha256, maximumBytes)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) Files.deleteIfExists(path)
    }

    companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES = 32L * 1024L * 1024L
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        internal fun assertIntegrity(
            path: Path,
            expectedSize: Long,
            expectedSha256: String,
            maximumBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
        ) {
            require(maximumBytes in 1..DEFAULT_MAX_ARTIFACT_BYTES) { "Artifact integrity limit is invalid" }
            require(expectedSize in 1..maximumBytes) { "Extension artifact has an invalid recorded size" }
            require(SHA256_HEX.matches(expectedSha256)) { "Extension artifact has an invalid recorded digest" }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Extension artifact is not a regular file" }
            require(Files.size(path) == expectedSize) { "Extension artifact size changed" }
            if (sha256(path, maximumBytes) != expectedSha256) {
                throw SecurityException("Extension artifact digest changed")
            }
        }

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
