package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import java.nio.file.Path

/** Authenticates and probes a direct JAR candidate without publishing server state. */
internal class ExtensionJarPreflightPipeline(
    private val transport: ExtensionArtifactTransport,
    private val artifactInspector: ArtifactInspector = DefaultArtifactInspector,
    private val shadowValidator: ShadowValidator = DefaultShadowValidator,
) {
    /** Preferred orchestration entry point: all repository-owned inputs share one transaction snapshot. */
    suspend fun prepare(
        input: ExtensionJarPreflightInputSnapshot,
        stagingDirectory: Path,
    ): ValidatedCandidate =
        prepare(
            expected = input.expected,
            expectedSigningKey = input.expectedSigningKey,
            stagingDirectory = stagingDirectory,
            occupiedSourceOwners = input.occupiedSourceOwners,
        )

    /** Low-level overload retained for isolated validator tests. Production orchestration uses the snapshot overload. */
    suspend fun prepare(
        expected: ExtensionInfo,
        expectedSigningKey: String,
        stagingDirectory: Path,
        occupiedSourceOwners: Map<Long, String>,
    ): ValidatedCandidate {
        ExtensionArtifactInputPolicy.requirePackageName(expected.pkgName)
        ExtensionJarShadowValidator.validateExpected(expected)
        val snapshot = expected.immutableCopy()
        val jarUrl = requireNotNull(snapshot.jarUrl) { "Extension store record has no direct JAR URL" }
        ExtensionArtifactInputPolicy.requireHttpsUrl(jarUrl)
        val signingKey = expectedSigningKey.lowercase()
        require(SHA256_HEX.matches(signingKey)) { "Extension store signing key is not a SHA-256 fingerprint" }
        val occupiedSnapshot = immutableOwners(occupiedSourceOwners)

        val staged =
            transport.download(
                ExtensionArtifactTransport.Request(
                    url = jarUrl,
                    stagingDirectory = stagingDirectory,
                    packageName = snapshot.pkgName,
                    kind = ArtifactKind.JAR,
                ),
            )
        try {
            check(staged.kind == ArtifactKind.JAR) { "Extension JAR transport returned a different artifact kind" }
            ExtensionArtifactInputPolicy.requireHttpsUrl(staged.finalUrl)
            staged.assertIntegrity()
            val metadata = artifactInspector.inspect(staged.path, snapshot, signingKey).immutableCopy()
            validateInspectorResult(metadata, snapshot, signingKey)
            staged.assertIntegrity()
            val probe = shadowValidator.validate(staged.path, metadata, snapshot, occupiedSnapshot)
            val validatedProbe = ExtensionJarShadowValidator.validateResult(probe, snapshot, occupiedSnapshot)
            staged.assertIntegrity()
            return ValidatedCandidate(
                artifact = staged,
                expected = snapshot,
                metadata = metadata,
                sources = validatedProbe.sources,
                extensionLanguage = validatedProbe.extensionLanguage,
            )
        } catch (failure: Throwable) {
            try {
                staged.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    class ValidatedCandidate internal constructor(
        private val artifact: StagedArtifact,
        val expected: ExtensionInfo,
        val metadata: ExtensionJarArtifactInspector.Metadata,
        val sources: List<ExtensionJarShadowValidator.SourceMetadata>,
        val extensionLanguage: String,
    ) : AutoCloseable {
        val path: Path
            get() = artifact.path
        val size: Long
            get() = artifact.size
        val sha256: String
            get() = artifact.sha256
        val finalUrl: String
            get() = artifact.finalUrl

        /** Must run immediately before a later activation consumes or moves this path. */
        fun assertIntegrity() = artifact.assertIntegrity()

        override fun close() = artifact.close()
    }

    internal fun interface ArtifactInspector {
        fun inspect(
            jarPath: Path,
            expected: ExtensionInfo,
            expectedSigningKey: String,
        ): ExtensionJarArtifactInspector.Metadata
    }

    internal fun interface ShadowValidator {
        fun validate(
            jarPath: Path,
            artifact: ExtensionJarArtifactInspector.Metadata,
            expected: ExtensionInfo,
            occupiedSourceOwners: Map<Long, String>,
        ): ExtensionJarShadowValidator.Result
    }

    private object DefaultArtifactInspector : ArtifactInspector {
        override fun inspect(
            jarPath: Path,
            expected: ExtensionInfo,
            expectedSigningKey: String,
        ): ExtensionJarArtifactInspector.Metadata = ExtensionJarArtifactInspector.inspect(jarPath, expected, expectedSigningKey)
    }

    private object DefaultShadowValidator : ShadowValidator {
        override fun validate(
            jarPath: Path,
            artifact: ExtensionJarArtifactInspector.Metadata,
            expected: ExtensionInfo,
            occupiedSourceOwners: Map<Long, String>,
        ): ExtensionJarShadowValidator.Result = ExtensionJarShadowValidator.validate(jarPath, artifact, expected, occupiedSourceOwners)
    }

    private fun ExtensionInfo.immutableCopy(): ExtensionInfo = copy(sources = java.util.List.copyOf(sources.map { it.copy() }))

    private fun ExtensionJarArtifactInspector.Metadata.immutableCopy(): ExtensionJarArtifactInspector.Metadata {
        require(signature.verifiedEntryNames.size in 1..MAX_VERIFIED_ENTRIES) {
            "Authenticated JAR has an invalid verified-entry snapshot"
        }
        return copy(
            signature =
                signature.copy(
                    verifiedEntryNames = java.util.Set.copyOf(signature.verifiedEntryNames),
                ),
        )
    }

    private fun immutableOwners(owners: Map<Long, String>): Map<Long, String> {
        require(owners.size <= MAX_OCCUPIED_SOURCE_OWNERS) { "Installed source ownership snapshot is too large" }
        owners.forEach { (sourceID, owner) ->
            require(sourceID > 0) { "Installed source ownership contains a non-positive ID" }
            ExtensionArtifactInputPolicy.requirePackageName(owner)
        }
        return java.util.Map.copyOf(owners)
    }

    private fun validateInspectorResult(
        metadata: ExtensionJarArtifactInspector.Metadata,
        expected: ExtensionInfo,
        signingKey: String,
    ) {
        fun requireIdentity(
            matches: Boolean,
            field: String,
        ) {
            if (!matches) throw SecurityException("Authenticated JAR $field differs from the immutable store snapshot")
        }

        requireIdentity(metadata.packageName == expected.pkgName, "package")
        requireIdentity(metadata.versionCode == expected.versionCode, "version code")
        requireIdentity(metadata.versionName == expected.versionName, "version name")
        requireIdentity(metadata.extensionLib == expected.extensionLib, "extension library")
        requireIdentity(metadata.name == expected.name, "name")
        requireIdentity(metadata.contentWarning == expected.contentWarning, "content warning")
        requireIdentity(metadata.signature.signerFingerprint == signingKey, "signer")
        require(metadata.signature.verifiedEntries in 1..MAX_VERIFIED_ENTRIES) {
            "Authenticated JAR has an invalid verified-entry count"
        }
        require(metadata.signature.uncompressedBytes in 1..MAX_UNCOMPRESSED_BYTES) {
            "Authenticated JAR has an invalid decoded-content size"
        }
        require(CLASS_NAME_PATTERN.matches(metadata.classFQName)) { "Authenticated JAR source class name is invalid" }
        requireIdentity(
            ExtensionJarArtifactInspector.resolveSourceClassName(
                metadata.classFQName,
                expected.pkgName,
                expected.extensionLib,
            ) == metadata.classFQName,
            "source class namespace",
        )
        val classEntry = metadata.classFQName.replace('.', '/') + ".class"
        requireIdentity(classEntry in metadata.signature.verifiedEntryNames, "source class authentication")
    }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        val CLASS_NAME_PATTERN = Regex("[A-Za-z_\$][A-Za-z0-9_\$]*(\\.[A-Za-z_\$][A-Za-z0-9_\$]*)+")
        const val MAX_OCCUPIED_SOURCE_OWNERS = 100_000
        const val MAX_VERIFIED_ENTRIES = 10_000
        const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
    }
}
