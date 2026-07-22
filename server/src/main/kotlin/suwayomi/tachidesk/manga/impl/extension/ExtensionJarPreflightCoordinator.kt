package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import java.nio.file.Path

/**
 * Authenticates one uniquely owned direct-JAR candidate and returns operation-owned staging.
 *
 * This coordinator deliberately has no publish, activation, journal, or ExtensionTable mutation.
 */
internal class ExtensionJarPreflightCoordinator(
    private val inputReader: (String) -> ExtensionJarPreflightInputSnapshot =
        ExtensionJarPreflightInputRepository::readUniquelyOwned,
    private val transportSelector: () -> ExtensionArtifactTransport =
        { ExtensionArtifactTransportSelector().select() },
    private val pipelineFactory: (ExtensionArtifactTransport) -> ExtensionJarPreflightPipeline =
        { ExtensionJarPreflightPipeline(it) },
    private val postflightGuard: (ExtensionJarPreflightInputSnapshot, Set<Long>) -> Unit =
        ExtensionJarPreflightInputRepository::verifyStillCurrent,
) {
    suspend fun prepare(
        packageName: String,
        stagingDirectory: Path,
    ): PreparedCandidate {
        ExtensionArtifactInputPolicy.requirePackageName(packageName)
        val input = inputReader(packageName)
        check(input.expected.pkgName == packageName) { "Preflight repository returned a different package identity" }
        val pipeline = pipelineFactory(transportSelector())
        val candidate = pipeline.prepare(input, stagingDirectory)

        try {
            candidate.assertIntegrity()
            postflightGuard(input, candidate.sources.mapTo(linkedSetOf()) { it.id })
            candidate.assertIntegrity()
            return PreparedCandidate(input, candidate)
        } catch (failure: Throwable) {
            try {
                candidate.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    class PreparedCandidate internal constructor(
        private val input: ExtensionJarPreflightInputSnapshot,
        private val candidate: ExtensionJarPreflightPipeline.ValidatedCandidate,
    ) : AutoCloseable {
        val ledgerEntryID: Long = input.ledgerEntryID
        val storeSnapshotToken: String = input.storeSnapshotToken
        val expected: ExtensionInfo
            get() = candidate.expected
        val path: Path
            get() = candidate.path
        val size: Long
            get() = candidate.size
        val sha256: String
            get() = candidate.sha256
        val finalUrl: String
            get() = candidate.finalUrl
        val sources: List<ExtensionJarShadowValidator.SourceMetadata>
            get() = candidate.sources
        val extensionLanguage: String
            get() = candidate.extensionLanguage

        /** Future activation must call this again immediately before consuming the path. */
        fun assertIntegrity() = candidate.assertIntegrity()

        /** Durable evidence handed to the activation journal before filesystem publication. */
        internal fun journalTrustBinding() =
            ExtensionActivationJournal.AuthenticatedStoreBinding(
                packageName = input.expected.pkgName,
                ledgerEntryID = input.ledgerEntryID,
                storeSnapshotToken = input.storeSnapshotToken,
                storeIndexUrl = input.expected.storeIndexUrl,
                storeSigningKey = input.expectedSigningKey,
                candidateSize = candidate.size,
                candidateSha256 = candidate.sha256,
                sourceIDs =
                    candidate.sources
                        .map { it.id }
                        .sorted(),
            )

        override fun close() = candidate.close()
    }
}
