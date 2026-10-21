package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Composes authenticated direct-JAR preflight, durable handoff, activation, and post-commit effects.
 *
 * Existing authenticated extension routes may call this coordinator only after
 * an explicit runtime capability opt-in. The caller supplies the exact
 * extension root and database identity already used by startup recovery.
 */
internal class ExtensionJarInstallationCoordinator(
    extensionsRoot: Path,
    private val databaseIdentity: String,
    private val preflightCoordinator: ExtensionJarPreflightCoordinator = ExtensionJarPreflightCoordinator(),
    private val planReader: PlanReader = PlanReader(ExtensionJarInstallationStateRepository::plan),
    private val postCommitEffects: ExtensionJarPostCommitEffects = ExtensionJarPostCommitEffects(),
) {
    private val extensionsRoot = extensionsRoot.toAbsolutePath().normalize()

    init {
        require(
            Files.isDirectory(this.extensionsRoot, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(this.extensionsRoot),
        ) {
            "Authenticated extension root must be an existing real directory"
        }
        require(SHA256_HEX.matches(databaseIdentity)) { "Extension database identity must be a SHA-256 digest" }
    }

    suspend fun execute(
        packageName: String,
        intent: Intent,
    ): Outcome {
        ExtensionArtifactInputPolicy.requirePackageName(packageName)
        repeat(MAX_SNAPSHOT_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return executeOnce(packageName, intent)
            } catch (failure: StaleExtensionStoreSnapshotException) {
                // Cleanup/rollback failures retain journal ownership and must never be retried blindly.
                if (failure.suppressed.isNotEmpty() || attempt == MAX_SNAPSHOT_ATTEMPTS - 1) throw failure
            }
        }
        error("Extension snapshot retry exhausted")
    }

    private suspend fun executeOnce(
        packageName: String,
        intent: Intent,
    ): Outcome {
        val prepared = preflightCoordinator.prepare(packageName, extensionsRoot)
        prepared.use {
            check(prepared.expected.pkgName == packageName) {
                "Authenticated extension preflight returned a different package"
            }
            val plan = planReader.read(packageName, prepared.expected, intent, extensionsRoot)
            val journaled =
                prepared.transferToJournal(
                    databaseIdentity = databaseIdentity,
                    activeArtifact = plan.activeArtifact,
                    previousArtifact = plan.previousArtifact,
                )
            var effects: ExtensionJarPostCommitEffects.Report? = null
            val metadata =
                ExtensionJarActivationOrchestrator.activate(journaled, intent.writerPolicy) { committed ->
                    effects =
                        postCommitEffects.apply(
                            ExtensionJarPostCommitEffects.Context(
                                packageName = packageName,
                                activeArtifact = plan.activeArtifact,
                                previousArtifact = plan.previousArtifact,
                                previousSourceIDs = committed.previousSourceIDs,
                                installedSourceIDs = committed.installedSourceIDs,
                            ),
                        )
                }
            return Outcome(metadata, checkNotNull(effects) { "Committed extension runtime effects were not attempted" })
        }
    }

    enum class Intent(
        internal val writerPolicy: ExtensionJarMetadataWriter.ExistingArtifactPolicy,
    ) {
        INSTALL(ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE),
        UPGRADE(ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE),
        REINSTALL(ExtensionJarMetadataWriter.ExistingArtifactPolicy.REINSTALL_SAME_VERSION),
        REPLACE_CUSTOM(ExtensionJarMetadataWriter.ExistingArtifactPolicy.REPLACE_CUSTOM_PINNED),
    }

    data class Outcome(
        val metadata: ExtensionJarMetadataWriter.Result,
        val postCommit: ExtensionJarPostCommitEffects.Report,
    )

    internal fun interface PlanReader {
        fun read(
            packageName: String,
            expected: ExtensionInfo,
            intent: Intent,
            extensionsRoot: Path,
        ): ExtensionJarInstallationStateRepository.ArtifactPlan
    }

    private companion object {
        const val MAX_SNAPSHOT_ATTEMPTS = 3
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
