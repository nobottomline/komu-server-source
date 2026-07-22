package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection

/** Builds a fail-closed artifact plan from current extension ownership immediately before journal handoff. */
internal object ExtensionJarInstallationStateRepository {
    fun plan(
        packageName: String,
        expected: ExtensionInfo,
        intent: ExtensionJarInstallationCoordinator.Intent,
        extensionsRoot: Path,
    ): ArtifactPlan {
        ExtensionArtifactInputPolicy.requirePackageName(packageName)
        check(expected.pkgName == packageName) { "Installation state request changed package identity" }
        val root = extensionsRoot.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Authenticated extension root must be an existing real directory"
        }

        val snapshot = readSnapshot(packageName)
        requireIntent(snapshot.target, expected, intent)
        val active = root.resolve("$packageName.jar").normalize()
        check(active.parent == root) { "Canonical extension artifact escaped the extension root" }

        val foreignClaim =
            snapshot.installedArtifacts
                .firstOrNull { it.packageName != packageName && it.jarFileName == active.fileName.toString() }
        check(foreignClaim == null) {
            "Canonical extension artifact is claimed by installed package ${foreignClaim?.packageName}"
        }

        val previous =
            snapshot.target
                ?.takeIf { it.isInstalled }
                ?.let { target ->
                    val jarName =
                        requireNotNull(target.jarFileName) {
                            "Installed extension $packageName has no safe compatibility artifact name"
                        }
                    root.resolve(jarName).normalize().also { path ->
                        check(path.parent == root) { "Previous extension artifact escaped the extension root" }
                        require(isRegularFile(path)) { "Installed extension artifact is missing or unsafe" }
                    }
                }

        if (isRegularFile(active)) {
            check(previous == active) { "Canonical extension artifact already exists without target ownership" }
        } else {
            check(!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) {
                "Canonical extension artifact path contains a non-regular entry"
            }
        }

        return ArtifactPlan(activeArtifact = active, previousArtifact = previous)
    }

    private fun readSnapshot(packageName: String): Snapshot =
        transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val targetRows =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName eq packageName }
                    .toList()
            check(targetRows.size <= 1) { "Multiple extension rows exist for $packageName" }
            val installedArtifacts =
                ExtensionTable
                    .select(ExtensionTable.pkgName, ExtensionTable.apkName)
                    .where { ExtensionTable.isInstalled eq true }
                    .mapNotNull { row ->
                        compatibilityJarName(row[ExtensionTable.apkName])?.let { jarName ->
                            InstalledArtifact(row[ExtensionTable.pkgName], jarName)
                        }
                    }
            Snapshot(targetRows.singleOrNull()?.toState(), java.util.List.copyOf(installedArtifacts))
        }

    private fun requireIntent(
        state: ExistingState?,
        expected: ExtensionInfo,
        intent: ExtensionJarInstallationCoordinator.Intent,
    ) {
        when (intent) {
            ExtensionJarInstallationCoordinator.Intent.INSTALL -> {
                check(state?.isInstalled != true) { "Install intent cannot replace an installed extension" }
                check(state?.isCustomPinned != true) { "Install intent cannot replace a custom-pinned extension" }
                check(state?.storeIndexUrl == null || state.storeIndexUrl == expected.storeIndexUrl) {
                    "Install intent cannot switch extension stores"
                }
            }

            ExtensionJarInstallationCoordinator.Intent.UPGRADE -> {
                val installed = requireNotNull(state?.takeIf { it.isInstalled }) { "Upgrade intent requires an installed extension" }
                check(!installed.isCustomPinned) { "Upgrade intent cannot replace a custom-pinned extension" }
                check(installed.storeIndexUrl == expected.storeIndexUrl) { "Upgrade intent cannot switch extension stores" }
                check(expected.versionCode > installed.versionCode) { "Upgrade intent requires a newer versionCode" }
            }

            ExtensionJarInstallationCoordinator.Intent.REINSTALL -> {
                val installed = requireNotNull(state?.takeIf { it.isInstalled }) { "Reinstall intent requires an installed extension" }
                check(!installed.isCustomPinned) { "Reinstall intent cannot replace a custom-pinned extension" }
                check(installed.storeIndexUrl == expected.storeIndexUrl) { "Reinstall intent cannot switch extension stores" }
                check(expected.versionCode == installed.versionCode) { "Reinstall intent requires the installed versionCode" }
            }

            ExtensionJarInstallationCoordinator.Intent.REPLACE_CUSTOM -> {
                check(state?.isInstalled == true && state.isCustomPinned) {
                    "Custom replacement intent requires an installed custom-pinned extension"
                }
            }
        }
    }

    private fun ResultRow.toState() =
        ExistingState(
            versionCode = this[ExtensionTable.versionCode],
            isInstalled = this[ExtensionTable.isInstalled],
            isCustomPinned = this[ExtensionTable.isCustomPinned],
            storeIndexUrl = this[ExtensionTable.storeIndexUrl],
            jarFileName = compatibilityJarName(this[ExtensionTable.apkName]),
        )

    private fun compatibilityJarName(apkName: String?): String? {
        val name = apkName ?: return null
        if (!name.endsWith(".apk") || name.removeSuffix(".apk").isBlank()) return null
        if (Path.of(name).fileName.toString() != name || '/' in name || '\\' in name) return null
        return name.removeSuffix(".apk") + ".jar"
    }

    private fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    data class ArtifactPlan(
        val activeArtifact: Path,
        val previousArtifact: Path?,
    )

    private data class ExistingState(
        val versionCode: Long,
        val isInstalled: Boolean,
        val isCustomPinned: Boolean,
        val storeIndexUrl: String?,
        val jarFileName: String?,
    )

    private data class InstalledArtifact(
        val packageName: String,
        val jarFileName: String,
    )

    private data class Snapshot(
        val target: ExistingState?,
        val installedArtifacts: List<InstalledArtifact>,
    )
}
