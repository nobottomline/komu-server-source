package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

/** Coordinates a validated JAR publication with its database transaction. */
internal object ExtensionArtifactActivation {
    private val logger = KotlinLogging.logger {}

    fun <T> commit(
        packageName: String,
        databaseIdentity: String,
        stagedArtifact: Path,
        activeArtifact: Path,
        previousArtifact: Path?,
        commitMetadata: (markerKey: String) -> T,
        isCommitMarkerPresent: (markerKey: String) -> Boolean,
        removeCommitMarker: (markerKey: String) -> Unit,
    ): T {
        val root = activeArtifact.toAbsolutePath().normalize().parent
        val journal = ExtensionActivationJournal(root)
        var entry = journal.prepare(packageName, databaseIdentity, stagedArtifact, activeArtifact, previousArtifact)

        try {
            entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
            journal.publish(entry)
            entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_VISIBLE)

            val result = commitMetadata(entry.record.markerKey)

            // From this point the DB marker is authoritative. Journal/backup
            // cleanup is retryable startup work and must not roll back a
            // transaction that has already committed.
            runCatching {
                entry = journal.transition(entry, ExtensionActivationJournal.State.DB_COMMITTED)
                journal.finalizeCommitted(entry)
                removeCommitMarker(entry.record.markerKey)
            }.onFailure {
                logger.warn(it) {
                    "Extension ${entry.record.packageName} committed; activation cleanup deferred to startup recovery"
                }
            }
            return result
        } catch (failure: Throwable) {
            val databaseCommitted =
                runCatching { isCommitMarkerPresent(entry.record.markerKey) }
                    .getOrElse { markerFailure ->
                        failure.addSuppressed(markerFailure)
                        // The commit status is unknown. Preserve every recovery
                        // artifact and journal instead of guessing.
                        throw failure
                    }

            if (databaseCommitted) {
                runCatching { journal.finalizeCommitted(entry) }
                    .onFailure(failure::addSuppressed)
            } else {
                runCatching { journal.rollback(entry) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }
}
