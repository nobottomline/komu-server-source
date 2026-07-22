package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable

/** Writes authenticated direct-JAR metadata without owning filesystem or recovery state. */
internal object ExtensionJarMetadataWriter {
    fun write(
        transaction: JdbcTransaction,
        metadata: ExtensionJarActivationOrchestrator.ActivationMetadata,
        policy: ExistingArtifactPolicy,
    ): Result {
        check(TransactionManager.currentOrNull() === transaction) {
            "Authenticated extension metadata must use the orchestrator transaction"
        }
        val prepared = prepareMetadata(metadata)
        val existingRows =
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.pkgName eq prepared.packageName }
                .toList()
        check(existingRows.size <= 1) { "Multiple extension rows exist for ${prepared.packageName}" }
        val existing = existingRows.singleOrNull()
        requirePolicy(existing, prepared, policy)

        val existingID = existing?.get(ExtensionTable.id)
        val previousSourceIDs =
            existingID
                ?.let { extensionID ->
                    SourceTable
                        .selectAll()
                        .where { SourceTable.extension eq extensionID }
                        .map { it[SourceTable.id].value }
                        .sorted()
                }.orEmpty()
        requireSourceOwnership(prepared.sourceIDs, existingID)

        val extensionID =
            if (existingID == null) {
                ExtensionTable
                    .insertAndGetId { statement ->
                        statement[ExtensionTable.pkgName] = prepared.packageName
                        statement.write(prepared)
                    }
            } else {
                val updated =
                    ExtensionTable.update({ ExtensionTable.id eq existingID }) { statement ->
                        statement.write(prepared)
                    }
                check(updated == 1) { "Authenticated extension metadata update affected $updated rows" }
                existingID
            }

        SourceTable.deleteWhere { SourceTable.extension eq extensionID }
        prepared.sources.forEach { source ->
            SourceTable.insert { statement ->
                statement[id] = source.id
                statement[name] = source.name
                statement[lang] = source.lang
                statement[extension] = extensionID
                statement[contentWarning] = source.contentWarning.ordinal
                statement[isNsfw] = source.contentWarning >= ContentWarning.MIXED
            }
        }
        verifyPersisted(prepared, extensionID)
        return Result(
            extensionID = extensionID.value,
            previousSourceIDs = java.util.List.copyOf(previousSourceIDs),
            installedSourceIDs = java.util.List.copyOf(prepared.sourceIDs),
        )
    }

    private fun requirePolicy(
        existing: ResultRow?,
        prepared: PreparedMetadata,
        policy: ExistingArtifactPolicy,
    ) {
        if (existing == null) {
            check(policy == ExistingArtifactPolicy.INSTALL_OR_UPGRADE) {
                "$policy requires an existing extension"
            }
            return
        }

        val installed = existing[ExtensionTable.isInstalled]
        val customPinned = existing[ExtensionTable.isCustomPinned]
        val existingStore = existing[ExtensionTable.storeIndexUrl]
        val existingVersion = existing[ExtensionTable.versionCode]
        when (policy) {
            ExistingArtifactPolicy.INSTALL_OR_UPGRADE -> {
                check(!customPinned) { "Custom-pinned extension requires explicit store replacement" }
                check(existingStore == null || existingStore == prepared.storeIndexUrl) {
                    "Refusing to switch extension stores implicitly"
                }
                if (installed) {
                    check(existingStore == prepared.storeIndexUrl) {
                        "Installed extension has no matching authenticated store origin"
                    }
                    check(prepared.versionCode > existingVersion) {
                        "Installed extension upgrade must increase versionCode"
                    }
                }
            }

            ExistingArtifactPolicy.REINSTALL_SAME_VERSION -> {
                check(installed && !customPinned) { "Exact reinstall requires a store-installed extension" }
                check(existingStore == prepared.storeIndexUrl) { "Exact reinstall cannot switch stores" }
                check(existingVersion == prepared.versionCode) { "Exact reinstall cannot change versionCode" }
            }

            ExistingArtifactPolicy.REPLACE_CUSTOM_PINNED -> {
                check(installed && customPinned) { "Store replacement requires a custom-pinned extension" }
            }
        }
    }

    private fun requireSourceOwnership(
        sourceIDs: List<Long>,
        existingExtensionID: EntityID<Int>?,
    ) {
        val foreignOwner =
            SourceTable
                .selectAll()
                .where { SourceTable.id inList sourceIDs }
                .firstOrNull { row -> row[SourceTable.extension] != existingExtensionID }
        check(foreignOwner == null) {
            "Authenticated extension source ID ${foreignOwner?.get(SourceTable.id)?.value} belongs to another extension"
        }
    }

    private fun prepareMetadata(metadata: ExtensionJarActivationOrchestrator.ActivationMetadata): PreparedMetadata {
        val expected = metadata.expected
        val artifact = metadata.artifact
        ExtensionArtifactInputPolicy.requirePackageName(expected.pkgName)
        require(expected.pkgName.length <= MAX_PACKAGE_NAME_LENGTH) { "Extension package exceeds the database column" }
        requireValue(expected.name, MAX_EXTENSION_NAME_LENGTH, "extension name")
        requireValue(expected.versionName, MAX_VERSION_NAME_LENGTH, "extension version name")
        requireValue(expected.extensionLib, MAX_EXTENSION_LIB_LENGTH, "extension library")
        requireValue(metadata.extensionLanguage, MAX_LANGUAGE_LENGTH, "extension language")
        requireValue(artifact.classFQName, MAX_CLASS_NAME_LENGTH, "extension class name")
        requireValue(metadata.activeJarName, MAX_ARTIFACT_NAME_LENGTH, "active JAR name")
        require(
            metadata.activeJarName.removeSuffix(".jar").isNotBlank() &&
                metadata.activeJarName.endsWith(".jar") &&
                '/' !in metadata.activeJarName &&
                '\\' !in metadata.activeJarName,
        ) {
            "Active extension artifact must be a plain JAR filename"
        }
        val compatibilityApkName = metadata.activeJarName.removeSuffix(".jar") + ".apk"
        requireValue(compatibilityApkName, MAX_ARTIFACT_NAME_LENGTH, "compatibility APK name")
        requireValue(expected.storeIndexUrl, MAX_REPO_URL_LENGTH, "extension store URL")
        requireValue(expected.apkUrl, MAX_URL_LENGTH, "extension APK URL")
        val jarUrl = requireNotNull(expected.jarUrl) { "Authenticated extension has no JAR URL" }
        requireValue(jarUrl, MAX_URL_LENGTH, "extension JAR URL")
        requireValue(expected.iconUrl, MAX_URL_LENGTH, "extension icon URL")
        ExtensionArtifactInputPolicy.requireHttpsUrl(expected.storeIndexUrl)
        ExtensionArtifactInputPolicy.requireHttpsUrl(expected.apkUrl)
        ExtensionArtifactInputPolicy.requireHttpsUrl(jarUrl)
        ExtensionArtifactInputPolicy.requireHttpsUrl(expected.iconUrl)

        require(artifact.packageName == expected.pkgName) { "Artifact package differs from authenticated metadata" }
        require(artifact.versionCode == expected.versionCode) { "Artifact version code differs from authenticated metadata" }
        require(artifact.versionName == expected.versionName) { "Artifact version name differs from authenticated metadata" }
        require(artifact.extensionLib == expected.extensionLib) { "Artifact library differs from authenticated metadata" }
        require(artifact.name == expected.name) { "Artifact name differs from authenticated metadata" }
        require(artifact.contentWarning == expected.contentWarning) {
            "Artifact content warning differs from authenticated metadata"
        }

        val expectedSources = expected.sources.associateBy { it.id }
        check(expectedSources.size == expected.sources.size) { "Authenticated store metadata contains duplicate source IDs" }
        val sources =
            metadata.sources.map { source ->
                require(source.id > 0) { "Validated source ID must be positive" }
                val expectedSource =
                    requireNotNull(expectedSources[source.id]) {
                        "Validated source ${source.id} is absent from authenticated store metadata"
                    }
                require(source.name == expectedSource.name && source.lang == expectedSource.lang) {
                    "Validated source ${source.id} differs from authenticated store metadata"
                }
                requireValue(source.name, MAX_SOURCE_NAME_LENGTH, "source name")
                requireValue(source.lang, MAX_LANGUAGE_LENGTH, "source language")
                PreparedSource(source.id, source.name, source.lang, expectedSource.contentWarning)
            }
        check(sources.size == expectedSources.size) { "Authenticated source set changed before metadata commit" }
        val sourceIDs = sources.map { it.id }
        check(sourceIDs.size == sourceIDs.distinct().size) { "Validated metadata contains duplicate source IDs" }
        val sourceLangs =
            sources
                .map { it.lang }
                .distinct()
                .joinToString(",")
                .ifEmpty { null }
        require(sourceLangs == null || sourceLangs.length <= MAX_SOURCE_LANGS_LENGTH) {
            "Extension source languages exceed the database column"
        }
        val baseUrl = expected.sources.firstOrNull()?.homeUrl
        require(baseUrl == null || baseUrl.length <= MAX_BASE_URL_LENGTH) {
            "Extension base URL exceeds the database column"
        }

        return PreparedMetadata(
            packageName = expected.pkgName,
            compatibilityApkName = compatibilityApkName,
            storeIndexUrl = expected.storeIndexUrl,
            name = expected.name,
            apkUrl = expected.apkUrl,
            jarUrl = jarUrl,
            iconUrl = expected.iconUrl,
            extensionLib = expected.extensionLib,
            versionName = expected.versionName,
            versionCode = expected.versionCode,
            language = metadata.extensionLanguage,
            contentWarning = expected.contentWarning,
            classFQName = artifact.classFQName,
            baseUrl = baseUrl,
            sourceLangs = sourceLangs,
            sources = java.util.List.copyOf(sources),
            sourceIDs = java.util.List.copyOf(sourceIDs.sorted()),
        )
    }

    private fun UpdateBuilder<*>.write(metadata: PreparedMetadata) {
        this[ExtensionTable.apkName] = metadata.compatibilityApkName
        this[ExtensionTable.repo] = metadata.storeIndexUrl
        this[ExtensionTable.storeIndexUrl] = metadata.storeIndexUrl
        this[ExtensionTable.iconUrl] = metadata.iconUrl
        this[ExtensionTable.name] = metadata.name
        this[ExtensionTable.apkUrl] = metadata.apkUrl
        this[ExtensionTable.jarUrl] = metadata.jarUrl
        this[ExtensionTable.extensionLib] = metadata.extensionLib
        this[ExtensionTable.versionName] = metadata.versionName
        this[ExtensionTable.versionCode] = metadata.versionCode
        this[ExtensionTable.lang] = metadata.language
        this[ExtensionTable.contentWarning] = metadata.contentWarning.ordinal
        this[ExtensionTable.isNsfw] = metadata.contentWarning >= ContentWarning.MIXED
        this[ExtensionTable.isInstalled] = true
        this[ExtensionTable.hasUpdate] = false
        this[ExtensionTable.isObsolete] = false
        this[ExtensionTable.classFQName] = metadata.classFQName
        this[ExtensionTable.baseUrl] = metadata.baseUrl
        this[ExtensionTable.sourceLangs] = metadata.sourceLangs
        this[ExtensionTable.isCustomPinned] = false
    }

    private fun verifyPersisted(
        expected: PreparedMetadata,
        extensionID: EntityID<Int>,
    ) {
        val row = ExtensionTable.selectAll().where { ExtensionTable.id eq extensionID }.single()
        check(row[ExtensionTable.pkgName] == expected.packageName)
        check(row[ExtensionTable.apkName] == expected.compatibilityApkName)
        check(row[ExtensionTable.repo] == expected.storeIndexUrl)
        check(row[ExtensionTable.storeIndexUrl] == expected.storeIndexUrl)
        check(row[ExtensionTable.iconUrl] == expected.iconUrl)
        check(row[ExtensionTable.name] == expected.name)
        check(row[ExtensionTable.apkUrl] == expected.apkUrl)
        check(row[ExtensionTable.jarUrl] == expected.jarUrl)
        check(row[ExtensionTable.versionCode] == expected.versionCode)
        check(row[ExtensionTable.versionName] == expected.versionName)
        check(row[ExtensionTable.extensionLib] == expected.extensionLib)
        check(row[ExtensionTable.lang] == expected.language)
        check(row[ExtensionTable.contentWarning] == expected.contentWarning.ordinal)
        check(row[ExtensionTable.isNsfw] == (expected.contentWarning >= ContentWarning.MIXED))
        check(row[ExtensionTable.classFQName] == expected.classFQName)
        check(row[ExtensionTable.baseUrl] == expected.baseUrl)
        check(row[ExtensionTable.sourceLangs] == expected.sourceLangs)
        check(row[ExtensionTable.isInstalled] && !row[ExtensionTable.hasUpdate] && !row[ExtensionTable.isObsolete])
        check(!row[ExtensionTable.isCustomPinned])

        val sources =
            SourceTable
                .selectAll()
                .where { SourceTable.extension eq extensionID }
                .sortedBy { it[SourceTable.id].value }
        check(sources.map { it[SourceTable.id].value } == expected.sourceIDs)
        val expectedByID = expected.sources.associateBy { it.id }
        sources.forEach { source ->
            val expectedSource = expectedByID.getValue(source[SourceTable.id].value)
            check(source[SourceTable.name] == expectedSource.name)
            check(source[SourceTable.lang] == expectedSource.lang)
            check(source[SourceTable.contentWarning] == expectedSource.contentWarning.ordinal)
            check(source[SourceTable.isNsfw] == (expectedSource.contentWarning >= ContentWarning.MIXED))
        }
    }

    private fun requireValue(
        value: String,
        maximumLength: Int,
        field: String,
    ) {
        require(value.isNotBlank() && value.length <= maximumLength && value.none(Char::isISOControl)) {
            "$field is invalid for metadata persistence"
        }
    }

    enum class ExistingArtifactPolicy {
        INSTALL_OR_UPGRADE,
        REINSTALL_SAME_VERSION,
        REPLACE_CUSTOM_PINNED,
    }

    data class Result(
        val extensionID: Int,
        val previousSourceIDs: List<Long>,
        val installedSourceIDs: List<Long>,
    )

    private data class PreparedMetadata(
        val packageName: String,
        val compatibilityApkName: String,
        val storeIndexUrl: String,
        val name: String,
        val apkUrl: String,
        val jarUrl: String,
        val iconUrl: String,
        val extensionLib: String,
        val versionName: String,
        val versionCode: Long,
        val language: String,
        val contentWarning: ContentWarning,
        val classFQName: String,
        val baseUrl: String?,
        val sourceLangs: String?,
        val sources: List<PreparedSource>,
        val sourceIDs: List<Long>,
    )

    private data class PreparedSource(
        val id: Long,
        val name: String,
        val lang: String,
        val contentWarning: ContentWarning,
    )

    private const val MAX_PACKAGE_NAME_LENGTH = 128
    private const val MAX_ARTIFACT_NAME_LENGTH = 1024
    private const val MAX_REPO_URL_LENGTH = 1024
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_EXTENSION_NAME_LENGTH = 128
    private const val MAX_VERSION_NAME_LENGTH = 16
    private const val MAX_EXTENSION_LIB_LENGTH = 16
    private const val MAX_LANGUAGE_LENGTH = 32
    private const val MAX_CLASS_NAME_LENGTH = 1024
    private const val MAX_BASE_URL_LENGTH = 2048
    private const val MAX_SOURCE_LANGS_LENGTH = 4096
    private const val MAX_SOURCE_NAME_LENGTH = 128
}
