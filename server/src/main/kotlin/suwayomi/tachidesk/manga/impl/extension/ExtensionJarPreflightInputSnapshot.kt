package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.table.ExtensionStoreEntryTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreSourceTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import java.sql.Connection

/** Immutable input boundary between mutable repository state and authenticated artifact preflight. */
internal class ExtensionJarPreflightInputSnapshot(
    val ledgerEntryID: Long,
    val storeSnapshotToken: String,
    expected: ExtensionInfo,
    expectedSigningKey: String,
    occupiedSourceOwners: Map<Long, String>,
) {
    val expected: ExtensionInfo = expected.copy(sources = java.util.List.copyOf(expected.sources.map { it.copy() }))
    val expectedSigningKey: String = expectedSigningKey.lowercase()
    val occupiedSourceOwners: Map<Long, String> = java.util.Map.copyOf(LinkedHashMap(occupiedSourceOwners))

    init {
        ExtensionJarShadowValidator.validateExpected(this.expected)
        require(SHA256_HEX.matches(this.expectedSigningKey)) {
            "Extension store signing key snapshot is not a SHA-256 fingerprint"
        }
        require(UUID_TOKEN.matches(storeSnapshotToken)) { "Extension store snapshot token is invalid" }
    }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        val UUID_TOKEN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/** Reads expected metadata, its captured signer, and installed source ownership in one DB transaction. */
internal object ExtensionJarPreflightInputRepository {
    /** Automatic execution policy: package identity must belong to exactly one durable store partition. */
    fun readUniquelyOwned(packageName: String): ExtensionJarPreflightInputSnapshot {
        ExtensionArtifactInputPolicy.requirePackageName(packageName)

        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val entries =
                ExtensionStoreEntryTable
                    .selectAll()
                    .where { ExtensionStoreEntryTable.pkgName eq packageName }
                    .toList()
            when (entries.size) {
                0 -> throw NoSuchElementException("No durable extension-store snapshot for package $packageName")
                1 -> readSnapshot(entries.single())
                else -> throw AmbiguousExtensionStoreOwnershipException(packageName, entries.size)
            }
        }
    }

    /** Exact-store read reserved for diagnostics and a future explicit user-selection flow. */
    fun read(
        packageName: String,
        storeIndexUrl: String,
    ): ExtensionJarPreflightInputSnapshot {
        ExtensionArtifactInputPolicy.requirePackageName(packageName)
        ExtensionArtifactInputPolicy.requireHttpsUrl(storeIndexUrl)

        return transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val entry =
                ExtensionStoreEntryTable
                    .selectAll()
                    .where {
                        (ExtensionStoreEntryTable.pkgName eq packageName) and
                            (ExtensionStoreEntryTable.storeIndexUrl eq storeIndexUrl)
                    }.singleOrNull()
                    ?: throw NoSuchElementException(
                        "No durable extension-store snapshot for package $packageName in store $storeIndexUrl",
                    )

            readSnapshot(entry)
        }
    }

    /**
     * Read-only postflight fence. Publish must repeat these checks in its own commit transaction;
     * this fence only prevents an already stale candidate from escaping the inert coordinator.
     */
    fun verifyStillCurrent(
        input: ExtensionJarPreflightInputSnapshot,
        candidateSourceIDs: Set<Long>,
    ) {
        require(candidateSourceIDs.isNotEmpty()) { "Validated extension candidate has no source IDs" }

        transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ) {
            val currentEntries =
                ExtensionStoreEntryTable
                    .selectAll()
                    .where { ExtensionStoreEntryTable.pkgName eq input.expected.pkgName }
                    .toList()
            if (currentEntries.isEmpty()) throw StaleExtensionStoreSnapshotException(input.expected.pkgName)
            if (currentEntries.size > 1) {
                throw AmbiguousExtensionStoreOwnershipException(input.expected.pkgName, currentEntries.size)
            }
            val entry = currentEntries.single()
            val isSameGeneration =
                entry[ExtensionStoreEntryTable.id].value == input.ledgerEntryID &&
                    entry[ExtensionStoreEntryTable.pkgName] == input.expected.pkgName &&
                    entry[ExtensionStoreEntryTable.storeIndexUrl] == input.expected.storeIndexUrl &&
                    entry[ExtensionStoreEntryTable.snapshotToken] == input.storeSnapshotToken &&
                    entry[ExtensionStoreEntryTable.signingKey].lowercase() == input.expectedSigningKey
            if (!isSameGeneration) throw StaleExtensionStoreSnapshotException(input.expected.pkgName)

            val currentOwners =
                SourceTable
                    .innerJoin(ExtensionTable)
                    .select(SourceTable.id, ExtensionTable.pkgName)
                    .where {
                        (SourceTable.id inList candidateSourceIDs.toList()) and
                            (ExtensionTable.isInstalled eq true)
                    }.associate { row -> row[SourceTable.id].value to row[ExtensionTable.pkgName] }
            currentOwners.forEach { (sourceID, owner) ->
                check(owner == input.expected.pkgName) {
                    "Extension candidate source ID $sourceID became owned by installed package $owner"
                }
            }
        }
    }

    private fun JdbcTransaction.readSnapshot(entry: ResultRow): ExtensionJarPreflightInputSnapshot {
        val packageName = entry[ExtensionStoreEntryTable.pkgName]
        val sources =
            ExtensionStoreSourceTable
                .selectAll()
                .where { ExtensionStoreSourceTable.entry eq entry[ExtensionStoreEntryTable.id] }
                .orderBy(ExtensionStoreSourceTable.sourceId to SortOrder.ASC)
                .map { row ->
                    ExtensionSource(
                        id = row[ExtensionStoreSourceTable.sourceId],
                        name = row[ExtensionStoreSourceTable.name],
                        lang = row[ExtensionStoreSourceTable.lang],
                        homeUrl = row[ExtensionStoreSourceTable.homeUrl],
                        message = row[ExtensionStoreSourceTable.message],
                        contentWarning =
                            strictContentWarning(
                                row[ExtensionStoreSourceTable.contentWarning],
                                "store source ${row[ExtensionStoreSourceTable.sourceId]}",
                            ),
                    )
                }
        val expected =
            ExtensionInfo(
                storeIndexUrl = entry[ExtensionStoreEntryTable.storeIndexUrl],
                name = entry[ExtensionStoreEntryTable.name],
                pkgName = packageName,
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
                        "store entry $packageName",
                    ),
                sources = sources,
            )
        val occupiedOwners =
            SourceTable
                .innerJoin(ExtensionTable)
                .select(SourceTable.id, ExtensionTable.pkgName)
                .where {
                    (SourceTable.id greater 0L) and (ExtensionTable.isInstalled eq true)
                }.associate { row -> row[SourceTable.id].value to row[ExtensionTable.pkgName] }

        return ExtensionJarPreflightInputSnapshot(
            ledgerEntryID = entry[ExtensionStoreEntryTable.id].value,
            storeSnapshotToken = entry[ExtensionStoreEntryTable.snapshotToken],
            expected = expected,
            expectedSigningKey = entry[ExtensionStoreEntryTable.signingKey],
            occupiedSourceOwners = occupiedOwners,
        )
    }

    private fun strictContentWarning(
        ordinal: Int,
        owner: String,
    ): ContentWarning =
        ContentWarning.entries.getOrNull(ordinal)
            ?: throw IllegalStateException("Invalid content-warning value $ordinal in $owner")
}

internal class AmbiguousExtensionStoreOwnershipException(
    packageName: String,
    storeCount: Int,
) : SecurityException("Extension package $packageName is advertised by $storeCount stores; explicit ownership is required")

internal class StaleExtensionStoreSnapshotException(
    packageName: String,
) : IllegalStateException("Extension store snapshot became stale for package $packageName")
