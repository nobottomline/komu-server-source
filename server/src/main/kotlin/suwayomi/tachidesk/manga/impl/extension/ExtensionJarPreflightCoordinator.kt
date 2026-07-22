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
        private val ownershipLock = Any()
        private var ownershipState = OwnershipState.OWNED

        val ledgerEntryID: Long = input.ledgerEntryID
        val storeSnapshotToken: String = input.storeSnapshotToken
        val expected: ExtensionInfo
            get() = candidate.expected
        val path: Path
            get() =
                synchronized(ownershipLock) {
                    requireOwned()
                    candidate.path
                }
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

        /** Activation must call this again immediately before consuming the still-owned path. */
        fun assertIntegrity() =
            synchronized(ownershipLock) {
                requireOwned()
                candidate.assertIntegrity()
            }

        /** Durable evidence handed to the activation journal before filesystem publication. */
        internal fun journalTrustBinding() =
            synchronized(ownershipLock) {
                requireOwned()
                createJournalTrustBinding()
            }

        /**
         * Atomically transfers cleanup responsibility to a durable authenticated journal.
         *
         * Before this returns, [close] owns cleanup. After it returns, [JournaledCandidate.journal]
         * is the only artifact owner and [close] is intentionally a no-op.
         */
        fun transferToJournal(
            databaseIdentity: String,
            activeArtifact: Path,
            previousArtifact: Path?,
        ): JournaledCandidate =
            synchronized(ownershipLock) {
                requireOwned()
                var journal: ExtensionActivationJournal? = null
                try {
                    candidate.assertIntegrity()
                    val active = activeArtifact.toAbsolutePath().normalize()
                    val extensionsRoot = requireNotNull(active.parent) { "Active extension artifact has no parent" }
                    val activationJournal = ExtensionActivationJournal(extensionsRoot)
                    journal = activationJournal
                    val entry =
                        activationJournal.prepareAuthenticated(
                            packageName = input.expected.pkgName,
                            databaseIdentity = databaseIdentity,
                            stagedArtifact = candidate.path,
                            activeArtifact = active,
                            previousArtifact = previousArtifact,
                            storeTrustBinding = createJournalTrustBinding(),
                        )
                    ownershipState = OwnershipState.TRANSFERRED
                    JournaledCandidate(
                        journal = activationJournal,
                        entry = entry,
                        expected = candidate.expected,
                        metadata = candidate.metadata,
                        sources = candidate.sources,
                        extensionLanguage = candidate.extensionLanguage,
                        finalUrl = candidate.finalUrl,
                    )
                } catch (failure: Throwable) {
                    val journalMayOwnCandidate = journal?.let(::journalMayOwnCandidate) ?: false
                    if (journalMayOwnCandidate) {
                        // A durable record (or an unreadable journal directory) is authoritative.
                        // Startup recovery must decide cleanup; preflight must not delete its file.
                        ownershipState = OwnershipState.TRANSFERRED
                    } else {
                        ownershipState = OwnershipState.CLOSED
                        try {
                            candidate.close()
                        } catch (cleanupFailure: Throwable) {
                            failure.addSuppressed(cleanupFailure)
                        }
                    }
                    throw failure
                }
            }

        private fun journalMayOwnCandidate(journal: ExtensionActivationJournal): Boolean =
            runCatching {
                journal.loadAll().any { entry ->
                    entry.record.packageName == input.expected.pkgName &&
                        entry.record.stagedName == candidate.path.fileName.toString() &&
                        entry.record.candidateSha256 == candidate.sha256
                }
            }.getOrElse {
                // Unknown durable state is not permission to delete executable bytes.
                true
            }

        private fun createJournalTrustBinding() =
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

        private fun requireOwned() {
            check(ownershipState == OwnershipState.OWNED) {
                "Prepared extension candidate is no longer owned by the preflight operation"
            }
        }

        override fun close() {
            val shouldClose =
                synchronized(ownershipLock) {
                    if (ownershipState == OwnershipState.OWNED) {
                        ownershipState = OwnershipState.CLOSED
                        true
                    } else {
                        false
                    }
                }
            if (shouldClose) candidate.close()
        }

        private enum class OwnershipState {
            OWNED,
            TRANSFERRED,
            CLOSED,
        }
    }

    /** Immutable handoff: journal owns the path; metadata remains available to the future DB callback. */
    class JournaledCandidate internal constructor(
        internal val journal: ExtensionActivationJournal,
        val entry: ExtensionActivationJournal.Entry,
        val expected: ExtensionInfo,
        val metadata: ExtensionJarArtifactInspector.Metadata,
        val sources: List<ExtensionJarShadowValidator.SourceMetadata>,
        val extensionLanguage: String,
        val finalUrl: String,
    )
}
