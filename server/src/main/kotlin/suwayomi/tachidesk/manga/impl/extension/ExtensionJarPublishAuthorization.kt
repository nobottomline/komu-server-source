package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.ExtensionActivationMarkerTable
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID

/**
 * Database half of authenticated JAR publication.
 *
 * The caller must already have made [activeArtifact] visible through an authenticated activation
 * journal. Filesystem ownership transfer deliberately remains outside this boundary until the
 * final activation orchestrator is wired.
 */
internal object ExtensionJarPublishAuthorization {
    fun <T> commit(
        journalEntry: ExtensionActivationJournal.Entry,
        commitMetadata: JdbcTransaction.(markerKey: String) -> T,
    ): T {
        val record = journalEntry.record
        check(record.state == ExtensionActivationJournal.State.FILE_VISIBLE) {
            "Authenticated extension metadata may commit only after its artifact is visible"
        }
        val binding =
            record.storeTrustBinding
                ?: throw SecurityException("Extension activation journal has no authenticated store binding")
        check(binding.packageName == record.packageName && binding.candidateSha256 == record.candidateSha256) {
            "Extension activation journal trust binding does not match its operation"
        }
        val markerKey = record.markerKey
        requireCanonicalMarkerKey(markerKey)
        check(markerKey == ExtensionActivationJournal.MARKER_PREFIX + record.operationId) {
            "Extension activation marker does not match its journal operation"
        }
        val activeArtifact = resolveActiveArtifact(journalEntry)
        ExtensionActivationJournal(activeArtifact.parent).requireCurrentOwnership(journalEntry)

        return transaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
            ExtensionJarPreflightInputRepository.verifyStillCurrent(this, binding)
            assertActiveArtifact(binding, activeArtifact)

            val result = commitMetadata(markerKey)
            val marker =
                ExtensionActivationMarkerTable
                    .selectAll()
                    .where { ExtensionActivationMarkerTable.markerKey eq markerKey }
                    .singleOrNull()
                    ?: throw IllegalStateException("Authenticated extension metadata commit did not insert its activation marker")
            check(marker[ExtensionActivationMarkerTable.packageName] == binding.packageName) {
                "Authenticated extension activation marker belongs to a different package"
            }

            // Fence callback code that changed store/source state or active bytes before commit.
            ExtensionJarPreflightInputRepository.verifyStillCurrent(this, binding)
            assertActiveArtifact(binding, activeArtifact)
            result
        }
    }

    private fun resolveActiveArtifact(journalEntry: ExtensionActivationJournal.Entry): Path {
        val journalPath = journalEntry.path.toAbsolutePath().normalize()
        val journalRoot = journalPath.parent ?: error("Extension activation journal path has no parent")
        val extensionsRoot = journalRoot.parent ?: error("Extension activation journal path has no extensions root")
        val activeArtifact = extensionsRoot.resolve(journalEntry.record.activeName).normalize()
        require(activeArtifact.parent == extensionsRoot) { "Extension activation active path escaped its root" }
        return activeArtifact
    }

    private fun assertActiveArtifact(
        binding: ExtensionActivationJournal.AuthenticatedStoreBinding,
        activeArtifact: Path,
    ) {
        StagedArtifact.assertIntegrity(
            path = activeArtifact,
            expectedSize = binding.candidateSize,
            expectedSha256 = binding.candidateSha256,
        )
    }

    private fun requireCanonicalMarkerKey(markerKey: String) {
        require(markerKey.startsWith(ExtensionActivationJournal.MARKER_PREFIX)) {
            "Invalid extension activation marker prefix"
        }
        val operationID = markerKey.removePrefix(ExtensionActivationJournal.MARKER_PREFIX)
        val canonicalOperationID = runCatching { UUID.fromString(operationID).toString() }.getOrNull()
        require(canonicalOperationID == operationID) { "Invalid extension activation marker operation ID" }
        require(markerKey.length <= MAX_MARKER_KEY_LENGTH) {
            "Extension activation marker exceeds its database column"
        }
    }

    private const val MAX_MARKER_KEY_LENGTH = 64
}
