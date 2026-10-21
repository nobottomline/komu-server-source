package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import suwayomi.tachidesk.manga.model.table.ExtensionStoreEntryTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreSourceTable
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Replaces only successfully fetched store partitions inside the caller's reconciliation transaction. */
internal object ExtensionStoreArtifactLedger {
    private val mutationLock = ReentrantLock()

    /** Serializes short local ledger/publication transactions, never downloads or source execution. */
    fun <T> withMutationLock(action: () -> T): T = mutationLock.withLock(action)

    /** Availability is shared by APK and JAR routes; JAR execution constraints belong to preflight. */
    fun readUpdateCandidate(packageName: String): UpdateCandidate =
        transaction {
            val entries = ExtensionStoreEntryTable.selectAll().where { ExtensionStoreEntryTable.pkgName eq packageName }.toList()
            when (entries.size) {
                0 -> {
                    throw ExtensionStoreSnapshotUnavailableException()
                }

                1 -> {
                    entries.single().let {
                        UpdateCandidate(
                            it[ExtensionStoreEntryTable.storeIndexUrl],
                            it[ExtensionStoreEntryTable.versionCode],
                        )
                    }
                }

                else -> {
                    throw AmbiguousExtensionStoreOwnershipException(packageName, entries.size)
                }
            }
        }

    data class UpdateCandidate(
        val storeIndexUrl: String,
        val versionCode: Long,
    )

    fun replaceSuccessfulStores(
        transaction: JdbcTransaction,
        foundExtensions: List<ExtensionInfo>,
        refreshedStores: List<ExtensionStore>,
    ) {
        val storesByUrl =
            refreshedStores.associateByUnique("Refreshed stores") { it.indexUrl }
        require(storesByUrl.isNotEmpty()) { "Artifact-ledger replacement requires at least one refreshed store" }
        require(foundExtensions.all { it.storeIndexUrl in storesByUrl }) {
            "Store extension snapshot contains an entry without its refreshed store identity"
        }

        val entries =
            foundExtensions
                .groupBy { it.storeIndexUrl to it.pkgName }
                .map { (_, candidates) ->
                    candidates.maxWith(
                        compareBy<ExtensionInfo> { it.versionCode }
                            .thenBy { it.versionName }
                            .thenBy { it.jarUrl.orEmpty() },
                    )
                }.sortedWith(compareBy(ExtensionInfo::storeIndexUrl, ExtensionInfo::pkgName))

        entries.forEach { extension ->
            ExtensionArtifactInputPolicy.requirePackageName(extension.pkgName)
            extension.sources.associateByUnique("Store entry ${extension.pkgName} sources") { it.id }
        }

        with(transaction) {
            val previous = readAll(transaction).associateBy { it.storeIndexUrl to it.pkgName }
            val previousRows = ExtensionStoreEntryTable.selectAll().toList()
            val incoming = entries.associateBy { it.storeIndexUrl to it.pkgName }
            val unchanged =
                previousRows
                    .filter { row ->
                        val key = row[ExtensionStoreEntryTable.storeIndexUrl] to row[ExtensionStoreEntryTable.pkgName]
                        val next = incoming[key]
                        next != null &&
                            previous[key] == next.copy(sources = next.sources.sortedBy { it.id }) &&
                            row[ExtensionStoreEntryTable.signingKey].equals(storesByUrl.getValue(key.first).signingKey, ignoreCase = true)
                    }.mapTo(mutableSetOf()) { it[ExtensionStoreEntryTable.id] }
            val retiredIDs =
                previousRows
                    .filter {
                        it[ExtensionStoreEntryTable.storeIndexUrl] in storesByUrl && it[ExtensionStoreEntryTable.id] !in unchanged
                    }.map { it[ExtensionStoreEntryTable.id] }
            if (retiredIDs.isNotEmpty()) {
                ExtensionStoreEntryTable.deleteWhere { ExtensionStoreEntryTable.id inList retiredIDs }
            }
            val unchangedKeys =
                previousRows.filter { it[ExtensionStoreEntryTable.id] in unchanged }.mapTo(mutableSetOf()) {
                    it[ExtensionStoreEntryTable.storeIndexUrl] to it[ExtensionStoreEntryTable.pkgName]
                }
            val changedEntries = entries.filter { it.storeIndexUrl to it.pkgName !in unchangedKeys }
            if (changedEntries.isEmpty()) return

            val insertedRows =
                ExtensionStoreEntryTable.batchInsert(changedEntries) { extension ->
                    val store = storesByUrl.getValue(extension.storeIndexUrl)
                    this[ExtensionStoreEntryTable.storeIndexUrl] = extension.storeIndexUrl
                    this[ExtensionStoreEntryTable.signingKey] = store.signingKey
                    this[ExtensionStoreEntryTable.snapshotToken] = UUID.randomUUID().toString()
                    this[ExtensionStoreEntryTable.name] = extension.name
                    this[ExtensionStoreEntryTable.pkgName] = extension.pkgName
                    this[ExtensionStoreEntryTable.apkUrl] = extension.apkUrl
                    this[ExtensionStoreEntryTable.jarUrl] = extension.jarUrl
                    this[ExtensionStoreEntryTable.iconUrl] = extension.iconUrl
                    this[ExtensionStoreEntryTable.extensionLib] = extension.extensionLib
                    this[ExtensionStoreEntryTable.versionCode] = extension.versionCode
                    this[ExtensionStoreEntryTable.versionName] = extension.versionName
                    this[ExtensionStoreEntryTable.lang] = extension.lang
                    this[ExtensionStoreEntryTable.contentWarning] = extension.contentWarning.ordinal
                }
            check(insertedRows.size == changedEntries.size) { "Artifact ledger did not persist every validated store entry" }

            val sourcesWithEntry =
                insertedRows.zip(changedEntries).flatMap { (row, extension) ->
                    val entryID = row[ExtensionStoreEntryTable.id]
                    extension.sources.map { entryID to it }
                }
            if (sourcesWithEntry.isNotEmpty()) {
                ExtensionStoreSourceTable.batchInsert(sourcesWithEntry) { (entryID, source) ->
                    this[ExtensionStoreSourceTable.entry] = entryID
                    this[ExtensionStoreSourceTable.sourceId] = source.id
                    this[ExtensionStoreSourceTable.name] = source.name
                    this[ExtensionStoreSourceTable.lang] = source.lang
                    this[ExtensionStoreSourceTable.homeUrl] = source.homeUrl
                    this[ExtensionStoreSourceTable.message] = source.message
                    this[ExtensionStoreSourceTable.contentWarning] = source.contentWarning.ordinal
                }
            }
        }
    }

    /** Returns every durable store partition from the caller's current transaction snapshot. */
    fun readAll(transaction: JdbcTransaction): List<ExtensionInfo> =
        with(transaction) {
            val sourcesByEntry =
                ExtensionStoreSourceTable
                    .selectAll()
                    .toList()
                    .groupBy { it[ExtensionStoreSourceTable.entry] }
            ExtensionStoreEntryTable.selectAll().map { entry ->
                ExtensionInfo(
                    storeIndexUrl = entry[ExtensionStoreEntryTable.storeIndexUrl],
                    name = entry[ExtensionStoreEntryTable.name],
                    pkgName = entry[ExtensionStoreEntryTable.pkgName],
                    apkUrl = entry[ExtensionStoreEntryTable.apkUrl],
                    jarUrl = entry[ExtensionStoreEntryTable.jarUrl],
                    iconUrl = entry[ExtensionStoreEntryTable.iconUrl],
                    extensionLib = entry[ExtensionStoreEntryTable.extensionLib],
                    versionCode = entry[ExtensionStoreEntryTable.versionCode],
                    versionName = entry[ExtensionStoreEntryTable.versionName],
                    lang = entry[ExtensionStoreEntryTable.lang],
                    contentWarning =
                        strictContentWarning(
                            entry[ExtensionStoreEntryTable.contentWarning],
                            "store entry ${entry[ExtensionStoreEntryTable.pkgName]}",
                        ),
                    sources =
                        sourcesByEntry[entry[ExtensionStoreEntryTable.id]]
                            .orEmpty()
                            .sortedBy { it[ExtensionStoreSourceTable.sourceId] }
                            .map { source ->
                                ExtensionSource(
                                    id = source[ExtensionStoreSourceTable.sourceId],
                                    name = source[ExtensionStoreSourceTable.name],
                                    lang = source[ExtensionStoreSourceTable.lang],
                                    homeUrl = source[ExtensionStoreSourceTable.homeUrl],
                                    message = source[ExtensionStoreSourceTable.message],
                                    contentWarning =
                                        strictContentWarning(
                                            source[ExtensionStoreSourceTable.contentWarning],
                                            "store source ${source[ExtensionStoreSourceTable.sourceId]}",
                                        ),
                                )
                            },
                )
            }
        }

    private fun <T, K> Iterable<T>.associateByUnique(
        owner: String,
        key: (T) -> K,
    ): Map<K, T> {
        val result = LinkedHashMap<K, T>()
        forEach { item ->
            val itemKey = key(item)
            require(result.put(itemKey, item) == null) { "$owner contain duplicate identity $itemKey" }
        }
        return result
    }

    private fun strictContentWarning(
        ordinal: Int,
        owner: String,
    ): ContentWarning =
        ContentWarning.entries.getOrNull(ordinal)
            ?: throw IllegalStateException("Invalid content-warning value $ordinal in $owner")
}
