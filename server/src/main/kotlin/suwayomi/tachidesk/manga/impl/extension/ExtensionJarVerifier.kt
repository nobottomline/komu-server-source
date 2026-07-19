package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.util.lang.Hash
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarFile

/** Authenticates a staged extension JAR before manifest parsing or class loading. */
internal object ExtensionJarVerifier {
    data class Result(
        val signerFingerprint: String,
        val verifiedEntries: Int,
        val uncompressedBytes: Long,
        val verifiedEntryNames: Set<String>,
    )

    class AuthenticatedEntry internal constructor(
        val signature: Result,
        bytes: ByteArray,
    ) {
        private val authenticatedBytes = bytes.copyOf()

        fun copyBytes(): ByteArray = authenticatedBytes.copyOf()
    }

    fun verify(
        jarPath: Path,
        expectedSigningKey: String,
    ): Result = authenticate(jarPath, expectedSigningKey, requestedEntry = null, requestedEntryLimit = 0).signature

    /** Returns entry bytes authenticated in the same [JarFile] verification pass. */
    fun verifyAndRead(
        jarPath: Path,
        expectedSigningKey: String,
        entryName: String,
        entrySizeLimit: Long,
    ): AuthenticatedEntry {
        require(entryName.isNotBlank()) { "Authenticated entry name is blank" }
        require(entrySizeLimit in 1..MAX_ENTRY_BYTES) { "Authenticated entry size limit is invalid" }
        return authenticate(jarPath, expectedSigningKey, entryName, entrySizeLimit).let { authenticated ->
            AuthenticatedEntry(
                signature = authenticated.signature,
                bytes = requireNotNull(authenticated.requestedEntryBytes) { "Signed extension JAR has no '$entryName'" },
            )
        }
    }

    private fun authenticate(
        jarPath: Path,
        expectedSigningKey: String,
        requestedEntry: String?,
        requestedEntryLimit: Long,
    ): AuthenticationResult {
        val normalizedKey = expectedSigningKey.lowercase()
        require(normalizedKey.matches(SHA256_HEX)) { "Store signing key is not a SHA-256 fingerprint" }

        val attributes =
            Files.readAttributes(
                jarPath,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        require(attributes.isRegularFile) { "Extension JAR is not a regular file" }
        require(attributes.size() in 1..MAX_ARCHIVE_BYTES) { "Extension JAR exceeds the archive size limit" }

        return JarFile(jarPath.toFile(), true).use { jar ->
            requireNotNull(jar.manifest) { "Signed extension JAR has no manifest" }
            val names = mutableSetOf<String>()
            val verifiedEntryNames = mutableSetOf<String>()
            var verifiedEntries = 0
            var uncompressedBytes = 0L
            var requestedEntryBytes: ByteArray? = null
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                validateEntryName(entry)
                check(names.add(entry.name)) { "Extension JAR contains duplicate entry '${entry.name}'" }
                require(names.size <= MAX_ENTRIES) { "Extension JAR contains too many entries" }
                if (entry.isDirectory) continue
                require(entry.size <= MAX_ENTRY_BYTES || entry.size == -1L) {
                    "Extension JAR entry '${entry.name}' exceeds the size limit"
                }

                var entryBytes = 0L
                val capturedBytes =
                    if (entry.name == requestedEntry) {
                        ByteArrayOutputStream()
                    } else {
                        null
                    }
                jar.getInputStream(entry).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        entryBytes += read
                        uncompressedBytes += read
                        require(entryBytes <= MAX_ENTRY_BYTES) {
                            "Extension JAR entry '${entry.name}' exceeds the decoded size limit"
                        }
                        require(uncompressedBytes <= MAX_UNCOMPRESSED_BYTES) {
                            "Extension JAR exceeds the total decoded size limit"
                        }
                        if (capturedBytes != null) {
                            require(entryBytes <= requestedEntryLimit) {
                                "Authenticated JAR entry '${entry.name}' exceeds its parser size limit"
                            }
                            capturedBytes.write(buffer, 0, read)
                        }
                    }
                }

                if (entry.isSignatureMetadata()) continue
                val leafFingerprints =
                    entry.codeSigners
                        .orEmpty()
                        .mapNotNull { signer -> signer.signerCertPath.certificates.firstOrNull() }
                        .map { certificate -> Hash.sha256(certificate.encoded) }
                        .toSet()
                if (leafFingerprints != setOf(normalizedKey)) {
                    throw SecurityException(
                        "Extension JAR entry '${entry.name}' is unsigned or signed by a different store key",
                    )
                }
                verifiedEntryNames += entry.name
                capturedBytes?.let { requestedEntryBytes = it.toByteArray() }
                verifiedEntries++
            }

            require(verifiedEntries > 0) { "Extension JAR contains no signed content" }
            AuthenticationResult(
                signature =
                    Result(
                        signerFingerprint = normalizedKey,
                        verifiedEntries = verifiedEntries,
                        uncompressedBytes = uncompressedBytes,
                        verifiedEntryNames = Collections.unmodifiableSet(LinkedHashSet(verifiedEntryNames)),
                    ),
                requestedEntryBytes = requestedEntryBytes,
            )
        }
    }

    private data class AuthenticationResult(
        val signature: Result,
        val requestedEntryBytes: ByteArray?,
    )

    private fun validateEntryName(entry: JarEntry) {
        val name = entry.name
        require(name.isNotBlank()) { "Extension JAR contains an empty entry name" }
        require(!name.startsWith('/') && '\\' !in name) { "Extension JAR contains a non-portable absolute entry" }
        val pathSegments = name.removeSuffix("/").split('/')
        require(pathSegments.none { it.isBlank() || it == "." || it == ".." }) {
            "Extension JAR entry has an ambiguous or escaping path"
        }
    }

    private fun JarEntry.isSignatureMetadata(): Boolean {
        val upperName = name.uppercase()
        if (upperName == JarFile.MANIFEST_NAME) return true
        if (!upperName.startsWith(META_INF_PREFIX)) return false

        val metadataName = upperName.removePrefix(META_INF_PREFIX)
        if ('/' in metadataName) return false
        return metadataName.endsWith(".SF") ||
            metadataName.endsWith(".RSA") ||
            metadataName.endsWith(".DSA") ||
            metadataName.endsWith(".EC") ||
            metadataName.startsWith("SIG-")
    }

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private const val META_INF_PREFIX = "META-INF/"
    private const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 16L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
    private const val MAX_ENTRIES = 10_000
}
