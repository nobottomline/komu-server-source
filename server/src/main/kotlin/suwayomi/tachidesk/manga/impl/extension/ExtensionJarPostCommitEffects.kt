package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.manga.impl.util.ExtensionRuntimeRegistry
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import java.nio.file.Path

/** Applies retry-safe in-memory effects only after artifact and metadata commit. */
internal class ExtensionJarPostCommitEffects(
    private val closeClassLoader: (Path) -> Unit = { PackageTools.closeExtensionClassLoader(it) },
    private val unregisterSource: (Long) -> Unit = GetSource::unregisterSource,
    private val clearAvailableUpdate: (String) -> Unit = { ExtensionsList.updateMap.remove(it) },
    private val runtimeBoundary: RuntimeBoundary = RuntimeBoundary { action -> ExtensionRuntimeRegistry.withLock(action) },
) {
    fun apply(context: Context): Report =
        try {
            applyChecked(context)
        } catch (cause: Throwable) {
            logger.warn(cause) { "Committed direct-JAR runtime effects could not be prepared" }
            Report(
                attemptedArtifacts = 0,
                attemptedSources = 0,
                failures =
                    listOf(
                        Failure(
                            Step.PREPARE_EFFECTS,
                            context.packageName,
                            cause::class.java.name,
                            cause.message,
                        ),
                    ),
            )
        }

    private fun applyChecked(context: Context): Report {
        ExtensionArtifactInputPolicy.requirePackageName(context.packageName)
        val failures = mutableListOf<Failure>()
        val artifacts = listOfNotNull(context.previousArtifact, context.activeArtifact).distinct()
        val sources = (context.previousSourceIDs + context.installedSourceIDs).distinct().sorted()

        runtimeBoundary.run {
            artifacts.forEach { artifact ->
                capture(failures, Step.CLOSE_CLASS_LOADER, artifact.toString()) {
                    closeClassLoader(artifact)
                }
            }
            sources.forEach { sourceID ->
                capture(failures, Step.UNREGISTER_SOURCE, sourceID.toString()) {
                    unregisterSource(sourceID)
                }
            }
            capture(failures, Step.CLEAR_AVAILABLE_UPDATE, context.packageName) {
                clearAvailableUpdate(context.packageName)
            }
        }

        return Report(
            attemptedArtifacts = artifacts.size,
            attemptedSources = sources.size,
            failures = java.util.List.copyOf(failures),
        )
    }

    private fun capture(
        failures: MutableList<Failure>,
        step: Step,
        subject: String,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { cause ->
            logger.warn(cause) { "Committed direct-JAR runtime effect $step failed for $subject" }
            failures += Failure(step, subject, cause::class.java.name, cause.message)
        }
    }

    data class Context(
        val packageName: String,
        val activeArtifact: Path,
        val previousArtifact: Path?,
        val previousSourceIDs: List<Long>,
        val installedSourceIDs: List<Long>,
    )

    data class Report(
        val attemptedArtifacts: Int,
        val attemptedSources: Int,
        val failures: List<Failure>,
    ) {
        val isComplete: Boolean
            get() = failures.isEmpty()
    }

    data class Failure(
        val step: Step,
        val subject: String,
        val causeType: String,
        val message: String?,
    )

    enum class Step {
        PREPARE_EFFECTS,
        CLOSE_CLASS_LOADER,
        UNREGISTER_SOURCE,
        CLEAR_AVAILABLE_UPDATE,
    }

    internal fun interface RuntimeBoundary {
        fun run(action: () -> Unit)
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
