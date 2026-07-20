package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.local.LocalSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.extension.Extension.proxyExtensionIconUrl
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

object ExtensionsList {
    private val logger = KotlinLogging.logger {}

    var lastUpdateCheck: Long = 0
    var updateMap = ConcurrentHashMap<String, ExtensionInfo>()

    suspend fun fetchExtensions() {
        val allExtensions = mutableListOf<ExtensionInfo>()
        val refreshedStores = mutableListOf<ExtensionStore>()

        ExtensionStoreService.getAndRefresh().forEach { store ->
            try {
                val extensions = ExtensionStoreService.getExtensions(store)
                allExtensions.addAll(extensions)
                refreshedStores.add(store)
            } catch (e: Exception) {
                logger.warn(e) {
                    "Failed to fetch extensions for store: ${store.indexUrl}"
                }
            }
        }

        reconcileExtensionDatabase(allExtensions, refreshedStores)
    }

    suspend fun fetchExtensionsCached() {
        // update if 60 seconds has passed or requested offline and database is empty
        if (lastUpdateCheck + 60.seconds.inWholeMilliseconds < System.currentTimeMillis()) {
            logger.debug { "Getting extensions list from the internet" }
            lastUpdateCheck = System.currentTimeMillis()

            fetchExtensions()
        } else {
            logger.debug { "used cached extension list" }
        }
    }

    suspend fun getExtensionList(): List<ExtensionDataClass> {
        fetchExtensionsCached()
        return extensionTableAsDataClass()
    }

    fun extensionTableAsDataClass() =
        transaction {
            ExtensionTable.selectAll().filter { it[ExtensionTable.name] != LocalSource.EXTENSION_NAME }.map {
                ExtensionDataClass(
                    repo = it[ExtensionTable.repo] ?: it[ExtensionTable.storeIndexUrl],
                    apkName = it[ExtensionTable.apkName].orEmpty(),
                    iconUrl = proxyExtensionIconUrl(it[ExtensionTable.pkgName]),
                    name = it[ExtensionTable.name],
                    pkgName = it[ExtensionTable.pkgName],
                    versionName = it[ExtensionTable.versionName],
                    versionCode = it[ExtensionTable.versionCode],
                    lang = it[ExtensionTable.lang],
                    isNsfw = it[ExtensionTable.contentWarning] >= ContentWarning.MIXED.ordinal,
                    installed = it[ExtensionTable.isInstalled],
                    hasUpdate = it[ExtensionTable.hasUpdate],
                    obsolete = it[ExtensionTable.isObsolete],
                    baseUrl = it[ExtensionTable.baseUrl],
                    sourceLangs = it[ExtensionTable.sourceLangs],
                    isCustomPinned = it[ExtensionTable.isCustomPinned],
                    storeIndexUrl = it[ExtensionTable.storeIndexUrl],
                    apkUrl = it[ExtensionTable.apkUrl],
                    jarUrl = it[ExtensionTable.jarUrl],
                    extensionLib = it[ExtensionTable.extensionLib],
                    contentWarning = ContentWarning.valueOf(it[ExtensionTable.contentWarning]),
                )
            }
        }

    private val updateExtensionDatabaseMutex = Mutex()

    internal suspend fun reconcileExtensionDatabase(
        foundExtensions: List<ExtensionInfo>,
        refreshedStores: List<ExtensionStore> = emptyList(),
    ) {
        updateExtensionDatabaseMutex.withLock {
            transaction {
                val reconciliationExtensions: List<ExtensionInfo>
                val authoritativeStoreUrls: Set<String>?
                if (refreshedStores.isNotEmpty()) {
                    ExtensionStoreArtifactLedger.replaceSuccessfulStores(
                        transaction = this,
                        foundExtensions = foundExtensions,
                        refreshedStores = refreshedStores,
                    )
                    reconciliationExtensions = ExtensionStoreArtifactLedger.readAll(this)
                    authoritativeStoreUrls =
                        reconciliationExtensions.mapTo(mutableSetOf()) { it.storeIndexUrl } +
                        refreshedStores.map { it.indexUrl }
                } else {
                    // Compatibility path for callers/tests that predate the durable store ledger.
                    reconciliationExtensions = foundExtensions
                    authoritativeStoreUrls = null
                }
                val uniqueExtensions =
                    reconciliationExtensions
                        .groupBy { it.pkgName }
                        .mapValues { (_, extension) ->
                            extension.maxWith(
                                compareBy<ExtensionInfo> { it.versionCode }
                                    .thenBy { it.storeIndexUrl },
                            )
                        }.values
                val installedExtensions =
                    ExtensionTable
                        .selectAll()
                        .toList()
                        .associateBy { it[ExtensionTable.pkgName] }
                val deletionAuthoritativeStoreUrls =
                    authoritativeStoreUrls?.let { knownStoreUrls ->
                        val configuredStoreUrls =
                            ExtensionStoreTable.selectAll().mapTo(mutableSetOf()) {
                                it[ExtensionStoreTable.indexUrl]
                            }
                        val removedStoreUrls =
                            installedExtensions.values.mapNotNullTo(mutableSetOf()) {
                                it[ExtensionTable.storeIndexUrl]?.takeUnless(configuredStoreUrls::contains)
                            }
                        knownStoreUrls + removedStoreUrls
                    }
                val extensionsToUpdate = mutableListOf<Pair<ExtensionInfo, ResultRow>>()
                val extensionsToInsert = mutableListOf<ExtensionInfo>()
                val extensionsToDelete =
                    installedExtensions
                        .filter {
                            it.value[ExtensionTable.storeIndexUrl] != null &&
                                (
                                    deletionAuthoritativeStoreUrls == null ||
                                        it.value[ExtensionTable.storeIndexUrl] in deletionAuthoritativeStoreUrls
                                ) &&
                                !(it.value[ExtensionTable.isCustomPinned] && it.value[ExtensionTable.isInstalled])
                        }.mapNotNull { (pkgName, extension) ->
                            extension.takeUnless { uniqueExtensions.any { it.pkgName == pkgName } }
                        }
                val pinnedExtensionsMissingFromStores =
                    installedExtensions
                        .filter {
                            it.value[ExtensionTable.isCustomPinned] &&
                                it.value[ExtensionTable.isInstalled] &&
                                (
                                    deletionAuthoritativeStoreUrls == null ||
                                        it.value[ExtensionTable.storeIndexUrl] in deletionAuthoritativeStoreUrls
                                ) &&
                                uniqueExtensions.none { extension -> extension.pkgName == it.key }
                        }.keys
                uniqueExtensions.forEach {
                    val extension = installedExtensions[it.pkgName]
                    if (extension != null) {
                        extensionsToUpdate.add(it to extension)
                    } else {
                        extensionsToInsert.add(it)
                    }
                }
                if (extensionsToUpdate.isNotEmpty()) {
                    val extensionsInstalled =
                        extensionsToUpdate
                            .groupBy { it.second[ExtensionTable.isInstalled] }
                    val installedExtensionsToUpdate = extensionsInstalled[true].orEmpty()
                    if (installedExtensionsToUpdate.isNotEmpty()) {
                        BatchUpdateStatement(ExtensionTable)
                            .apply {
                                installedExtensionsToUpdate.forEach { (foundExtension, extensionRecord) ->
                                    addBatch(EntityID(extensionRecord[ExtensionTable.id].value, ExtensionTable))
                                    // Always update icon url and repo
                                    this[ExtensionTable.iconUrl] = foundExtension.iconUrl
                                    this[ExtensionTable.repo] = foundExtension.storeIndexUrl
                                    this[ExtensionTable.storeIndexUrl] = foundExtension.storeIndexUrl
                                    this[ExtensionTable.apkUrl] = foundExtension.apkUrl
                                    this[ExtensionTable.jarUrl] = foundExtension.jarUrl

                                    // Both flags are derived from the current store snapshot. Never
                                    // carry a stale true value into the next reconciliation cycle.
                                    this[ExtensionTable.hasUpdate] = false
                                    this[ExtensionTable.isObsolete] = false

                                    when {
                                        extensionRecord[ExtensionTable.isCustomPinned] -> {
                                            // A custom upload owns the active binary until the user
                                            // explicitly switches back to the store artifact.
                                            updateMap.remove(foundExtension.pkgName)
                                        }

                                        foundExtension.versionCode > extensionRecord[ExtensionTable.versionCode] -> {
                                            this[ExtensionTable.hasUpdate] = true
                                            updateMap[foundExtension.pkgName] = foundExtension
                                        }

                                        foundExtension.versionCode < extensionRecord[ExtensionTable.versionCode] -> {
                                            this[ExtensionTable.isObsolete] = true
                                            updateMap.remove(foundExtension.pkgName)
                                        }

                                        else -> {
                                            updateMap.remove(foundExtension.pkgName)
                                        }
                                    }
                                }
                            }.toExecutable()
                            .execute(this@transaction)
                    }
                    val extensionsToFullyUpdate = extensionsInstalled[false].orEmpty()
                    if (extensionsToFullyUpdate.isNotEmpty()) {
                        BatchUpdateStatement(ExtensionTable)
                            .apply {
                                extensionsToFullyUpdate.forEach { (foundExtension, extensionRecord) ->
                                    addBatch(EntityID(extensionRecord[ExtensionTable.id].value, ExtensionTable))
                                    // extension is not installed, so we can overwrite the data without a care
                                    this[ExtensionTable.repo] = foundExtension.storeIndexUrl
                                    this[ExtensionTable.storeIndexUrl] = foundExtension.storeIndexUrl
                                    this[ExtensionTable.name] = foundExtension.name
                                    this[ExtensionTable.extensionLib] = foundExtension.extensionLib
                                    this[ExtensionTable.versionName] = foundExtension.versionName
                                    this[ExtensionTable.versionCode] = foundExtension.versionCode
                                    this[ExtensionTable.lang] = foundExtension.lang
                                    this[ExtensionTable.contentWarning] = foundExtension.contentWarning.ordinal
                                    this[ExtensionTable.isNsfw] = foundExtension.isLegacyNsfw()
                                    this[ExtensionTable.apkUrl] = foundExtension.apkUrl
                                    this[ExtensionTable.jarUrl] = foundExtension.jarUrl
                                    this[ExtensionTable.iconUrl] = foundExtension.iconUrl
                                    this[ExtensionTable.baseUrl] = foundExtension.sources.firstOrNull()?.homeUrl
                                    this[ExtensionTable.sourceLangs] = foundExtension.sourceLangs()
                                    this[ExtensionTable.isCustomPinned] = false
                                    this[ExtensionTable.hasUpdate] = false
                                    this[ExtensionTable.isObsolete] = false
                                    updateMap.remove(foundExtension.pkgName)
                                }
                            }.toExecutable()
                            .execute(this@transaction)
                    }
                }
                if (extensionsToInsert.isNotEmpty()) {
                    ExtensionTable.batchInsert(extensionsToInsert) { foundExtension ->
                        this[ExtensionTable.repo] = foundExtension.storeIndexUrl
                        this[ExtensionTable.storeIndexUrl] = foundExtension.storeIndexUrl
                        this[ExtensionTable.name] = foundExtension.name
                        this[ExtensionTable.pkgName] = foundExtension.pkgName
                        this[ExtensionTable.extensionLib] = foundExtension.extensionLib
                        this[ExtensionTable.versionName] = foundExtension.versionName
                        this[ExtensionTable.versionCode] = foundExtension.versionCode
                        this[ExtensionTable.lang] = foundExtension.lang
                        this[ExtensionTable.contentWarning] = foundExtension.contentWarning.ordinal
                        this[ExtensionTable.isNsfw] = foundExtension.isLegacyNsfw()
                        this[ExtensionTable.apkUrl] = foundExtension.apkUrl
                        this[ExtensionTable.jarUrl] = foundExtension.jarUrl
                        this[ExtensionTable.iconUrl] = foundExtension.iconUrl
                        this[ExtensionTable.baseUrl] = foundExtension.sources.firstOrNull()?.homeUrl
                        this[ExtensionTable.sourceLangs] = foundExtension.sourceLangs()
                    }
                }

                // deal with obsolete extensions
                if (pinnedExtensionsMissingFromStores.isNotEmpty()) {
                    pinnedExtensionsMissingFromStores.forEach(updateMap::remove)
                    ExtensionTable.update({ ExtensionTable.pkgName inList pinnedExtensionsMissingFromStores }) {
                        it[hasUpdate] = false
                        it[isObsolete] = false
                    }
                }

                val extensionsToRemove =
                    extensionsToDelete
                        .groupBy { it[ExtensionTable.isInstalled] }
                        .mapValues { (_, extensions) -> extensions.map { it[ExtensionTable.pkgName] } }
                // not in the repo, so these extensions are obsolete
                val obsoleteExtensions = extensionsToRemove[true].orEmpty()
                if (obsoleteExtensions.isNotEmpty()) {
                    obsoleteExtensions.forEach(updateMap::remove)
                    ExtensionTable.update({ ExtensionTable.pkgName inList obsoleteExtensions }) {
                        it[hasUpdate] = false
                        it[isObsolete] = true
                    }
                }
                // is not installed, so we can remove the record without a care
                val removeExtensions = extensionsToRemove[false].orEmpty()
                if (removeExtensions.isNotEmpty()) {
                    removeExtensions.forEach(updateMap::remove)
                    ExtensionTable.deleteWhere { ExtensionTable.pkgName inList removeExtensions }
                }
            }
        }
    }

    private fun ExtensionInfo.isLegacyNsfw(): Boolean = contentWarning >= ContentWarning.MIXED

    private fun ExtensionInfo.sourceLangs(): String? =
        sources
            .map { it.lang }
            .distinct()
            .joinToString(",")
            .ifEmpty { null }
}
