package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import app.komu.library.DatabaseDurability
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.table.ExtensionActivationMarkerTable

/**
 * Executes one authenticated direct-JAR activation from its typed journal handoff.
 *
 * This is deliberately not wired to the production install route yet. The orchestrator owns
 * cross-resource ordering; the journal owns filesystem recovery and
 * [ExtensionJarPublishAuthorization] owns the serializable database boundary.
 */
internal object ExtensionJarActivationOrchestrator {
    private val logger = KotlinLogging.logger {}

    /**
     * Confirms the marker transaction is durable before the irreversible
     * cleanup (Komu review 5); tests replace it.
     */
    @Volatile
    internal var confirmDurable: () -> Unit = DatabaseDurability::syncCommitted

    /** Canonical authenticated persistence path for future guarded production wiring. */
    fun activate(
        candidate: ExtensionJarPreflightCoordinator.JournaledCandidate,
        policy: ExtensionJarMetadataWriter.ExistingArtifactPolicy,
    ): ExtensionJarMetadataWriter.Result = activate(candidate, policy) {}

    /** Runs non-durable runtime effects after DB commit but before retired artifact cleanup. */
    fun activate(
        candidate: ExtensionJarPreflightCoordinator.JournaledCandidate,
        policy: ExtensionJarMetadataWriter.ExistingArtifactPolicy,
        afterDatabaseCommit: (ExtensionJarMetadataWriter.Result) -> Unit,
    ): ExtensionJarMetadataWriter.Result =
        activateInternal(
            candidate = candidate,
            commitMetadata = { metadata -> ExtensionJarMetadataWriter.write(this, metadata, policy) },
            afterDatabaseCommit = afterDatabaseCommit,
        )

    /** Low-level orchestration seam retained for failure injection and non-production integration tests. */
    fun <T> activate(
        candidate: ExtensionJarPreflightCoordinator.JournaledCandidate,
        commitMetadata: JdbcTransaction.(metadata: ActivationMetadata) -> T,
    ): T = activateInternal(candidate, commitMetadata) {}

    private fun <T> activateInternal(
        candidate: ExtensionJarPreflightCoordinator.JournaledCandidate,
        commitMetadata: JdbcTransaction.(metadata: ActivationMetadata) -> T,
        afterDatabaseCommit: (T) -> Unit,
    ): T {
        val journal = candidate.journal
        var entry = candidate.entry

        try {
            validateHandoff(candidate)
            val metadata = candidate.toActivationMetadata()
            journal.requireCurrentOwnership(entry)
            entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
            journal.publish(entry)
            entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_VISIBLE)

            val result =
                ExtensionJarPublishAuthorization.commit(entry) { markerKey ->
                    val committedMetadata = commitMetadata(metadata)
                    ExtensionActivationMarkerTable.insert {
                        it[ExtensionActivationMarkerTable.markerKey] = markerKey
                        it[packageName] = metadata.expected.pkgName
                    }
                    committedMetadata
                }

            runCatching { afterDatabaseCommit(result) }
                .onFailure { failure ->
                    logger.warn(failure) {
                        "Authenticated extension ${entry.record.packageName} committed; runtime effects reported failure"
                    }
                }

            // The transaction marker is authoritative from this point. Cleanup is retryable
            // startup work and must never turn a committed activation into a guessed rollback.
            // Komu review 5: it is irreversible (journal, rollback copy, previous artifact),
            // so it needs the marker transaction durable; unconfirmed, everything is kept.
            runCatching {
                confirmDurable()
                cleanupCommitted(journal, entry)
            }
                .onFailure { failure ->
                    logger.warn(failure) {
                        "Authenticated extension ${entry.record.packageName} committed; cleanup deferred to recovery"
                    }
                }
            return result
        } catch (failure: Throwable) {
            val databaseCommitted =
                runCatching { markerOwner(entry.record.markerKey) }
                    .getOrElse { markerFailure ->
                        failure.addSuppressed(markerFailure)
                        // Unknown database state is not permission to mutate recovery artifacts.
                        throw failure
                    }

            if (databaseCommitted != null) {
                if (databaseCommitted != entry.record.packageName) {
                    failure.addSuppressed(
                        IllegalStateException("Extension activation marker belongs to a different package"),
                    )
                } else if (entry.record.state.ordinal < ExtensionActivationJournal.State.FILE_VISIBLE.ordinal) {
                    failure.addSuppressed(
                        IllegalStateException("Extension activation marker exists before artifact visibility"),
                    )
                } else {
                    runCatching {
                        confirmDurable()
                        cleanupCommitted(journal, entry)
                    }.onFailure(failure::addSuppressed)
                }
            } else {
                runCatching { journal.rollback(entry) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    private fun validateHandoff(candidate: ExtensionJarPreflightCoordinator.JournaledCandidate) {
        val record = candidate.entry.record
        check(record.state == ExtensionActivationJournal.State.PREPARED) {
            "Authenticated extension activation must start from PREPARED"
        }
        val binding =
            record.storeTrustBinding
                ?: throw SecurityException("Authenticated extension activation has no store trust binding")
        requireAuthenticated(record.packageName == candidate.expected.pkgName, "package identity")
        requireAuthenticated(candidate.metadata.packageName == candidate.expected.pkgName, "artifact package identity")
        requireAuthenticated(candidate.metadata.versionCode == candidate.expected.versionCode, "artifact version code")
        requireAuthenticated(candidate.metadata.versionName == candidate.expected.versionName, "artifact version name")
        requireAuthenticated(candidate.metadata.extensionLib == candidate.expected.extensionLib, "extension library")
        requireAuthenticated(candidate.metadata.name == candidate.expected.name, "extension name")
        requireAuthenticated(candidate.metadata.contentWarning == candidate.expected.contentWarning, "content warning")
        requireAuthenticated(
            candidate.metadata.signature.signerFingerprint == binding.storeSigningKey,
            "artifact signer",
        )
        val sourceIDs = candidate.sources.map { it.id }.sorted()
        requireAuthenticated(sourceIDs == binding.sourceIDs, "validated source ownership")
        requireAuthenticated(
            candidate.expected.sources
                .map { it.id }
                .sorted() == sourceIDs,
            "store source ownership",
        )
        requireAuthenticated(candidate.extensionLanguage == candidate.expected.lang, "extension language")
        ExtensionArtifactInputPolicy.requireHttpsUrl(candidate.finalUrl)
    }

    private fun ExtensionJarPreflightCoordinator.JournaledCandidate.toActivationMetadata() =
        ActivationMetadata(
            expected = expected.copy(sources = java.util.List.copyOf(expected.sources.map { it.copy() })),
            artifact =
                metadata.copy(
                    signature =
                        metadata.signature.copy(
                            verifiedEntryNames = java.util.Set.copyOf(metadata.signature.verifiedEntryNames),
                        ),
                ),
            sources = java.util.List.copyOf(sources.map { it.copy() }),
            extensionLanguage = extensionLanguage,
            finalUrl = finalUrl,
            activeJarName = entry.record.activeName,
        )

    private fun requireAuthenticated(
        condition: Boolean,
        field: String,
    ) {
        if (!condition) throw SecurityException("Journaled extension candidate changed its $field")
    }

    private fun cleanupCommitted(
        journal: ExtensionActivationJournal,
        initialEntry: ExtensionActivationJournal.Entry,
    ) {
        var entry = initialEntry
        if (entry.record.state == ExtensionActivationJournal.State.FILE_VISIBLE) {
            entry = journal.transition(entry, ExtensionActivationJournal.State.DB_COMMITTED)
        }
        check(entry.record.state == ExtensionActivationJournal.State.DB_COMMITTED) {
            "Committed extension cleanup requires DB_COMMITTED state"
        }
        journal.finalizeCommitted(entry)
        removeMarker(entry.record.markerKey, entry.record.packageName)
    }

    private fun markerOwner(markerKey: String): String? =
        transaction {
            ExtensionActivationMarkerTable
                .selectAll()
                .where { ExtensionActivationMarkerTable.markerKey eq markerKey }
                .singleOrNull()
                ?.get(ExtensionActivationMarkerTable.packageName)
        }

    private fun removeMarker(
        markerKey: String,
        packageName: String,
    ) {
        transaction {
            val owner =
                ExtensionActivationMarkerTable
                    .selectAll()
                    .where { ExtensionActivationMarkerTable.markerKey eq markerKey }
                    .singleOrNull()
                    ?.get(ExtensionActivationMarkerTable.packageName)
                    ?: return@transaction
            check(owner == packageName) { "Refusing to remove another package's activation marker" }
            ExtensionActivationMarkerTable.deleteWhere { ExtensionActivationMarkerTable.markerKey eq markerKey }
        }
    }

    /** Least-authority immutable input for the metadata callback. */
    class ActivationMetadata internal constructor(
        val expected: ExtensionInfo,
        val artifact: ExtensionJarArtifactInspector.Metadata,
        val sources: List<ExtensionJarShadowValidator.SourceMetadata>,
        val extensionLanguage: String,
        val finalUrl: String,
        val activeJarName: String,
    )
}
