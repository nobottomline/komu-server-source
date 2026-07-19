package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Coordinates the filesystem half of an extension activation with its
 * database transaction.
 *
 * The candidate must already have passed parsing, conversion and class-load
 * validation. [commitMetadata] must perform all database writes in one
 * transaction and must not return until that transaction has committed.
 *
 * This boundary guarantees exception-level rollback. It deliberately does not
 * claim process-crash atomicity: a durable activation journal is required for
 * recovery if the process dies between the file swap and the database commit.
 */
internal object ExtensionArtifactActivation {
    fun <T> commit(
        stagedArtifact: Path,
        activeArtifact: Path,
        previousArtifact: Path?,
        commitMetadata: () -> T,
    ): T {
        val staged = stagedArtifact.toAbsolutePath().normalize()
        val active = activeArtifact.toAbsolutePath().normalize()
        val previous = previousArtifact?.toAbsolutePath()?.normalize()

        require(Files.isRegularFile(staged)) { "Staged extension artifact does not exist: $staged" }
        require(staged != active) { "Staged and active extension artifacts must be different files" }
        require(active.parent != null) { "Active extension artifact must have a parent directory" }
        require(staged.parent == active.parent) {
            "Staged and active extension artifacts must share a filesystem directory"
        }

        Files.createDirectories(active.parent)

        val replacesPreviousInPlace = previous == active && Files.isRegularFile(active)
        val rollbackCopy =
            if (replacesPreviousInPlace) {
                Files.createTempFile(active.parent, ".${active.fileName}.rollback-", ".jar").also {
                    Files.copy(active, it, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            } else {
                null
            }

        try {
            try {
                moveReplacing(staged, active)
            } catch (activationFailure: Throwable) {
                restoreInPlaceBackup(active, rollbackCopy, activationFailure)
                throw activationFailure
            }

            val result =
                try {
                    commitMetadata()
                } catch (metadataFailure: Throwable) {
                    rollbackActivation(active, rollbackCopy, metadataFailure)
                    throw metadataFailure
                }

            // Metadata has committed. Cleanup failures must not pretend that
            // the transaction failed and must never roll back the new file.
            if (previous != null && previous != active) runCatching { Files.deleteIfExists(previous) }
            rollbackCopy?.let { runCatching { Files.deleteIfExists(it) } }
            return result
        } finally {
            runCatching { Files.deleteIfExists(staged) }
            rollbackCopy?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun rollbackActivation(
        active: Path,
        rollbackCopy: Path?,
        originalFailure: Throwable,
    ) {
        try {
            Files.deleteIfExists(active)
            if (rollbackCopy != null) moveReplacing(rollbackCopy, active)
        } catch (rollbackFailure: Throwable) {
            originalFailure.addSuppressed(rollbackFailure)
        }
    }

    private fun restoreInPlaceBackup(
        active: Path,
        rollbackCopy: Path?,
        originalFailure: Throwable,
    ) {
        if (rollbackCopy == null) return
        try {
            Files.deleteIfExists(active)
            moveReplacing(rollbackCopy, active)
        } catch (rollbackFailure: Throwable) {
            originalFailure.addSuppressed(rollbackFailure)
        }
    }

    private fun moveReplacing(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
