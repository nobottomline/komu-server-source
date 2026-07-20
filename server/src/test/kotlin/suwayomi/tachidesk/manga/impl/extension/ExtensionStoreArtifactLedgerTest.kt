package suwayomi.tachidesk.manga.impl.extension

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import suwayomi.tachidesk.manga.model.table.ExtensionStoreEntryTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class ExtensionStoreArtifactLedgerTest : ApplicationTest() {
    @Test
    fun `ledger keeps active runtime separate and binds each store entry to its fetched signer`() =
        runBlocking {
            withFixtureDatabase {
                val storeA = store(STORE_A, "a".repeat(64))
                val storeB = store(STORE_B, "b".repeat(64))
                insertStore(storeA)
                insertStore(storeB)
                insertInstalledExtension(TARGET_PKG, versionCode = 1, sourceID = 101)
                insertInstalledExtension(OTHER_PKG, versionCode = 1, sourceID = 777)

                val mutableSources = mutableListOf(source(101, "Store A Source"))
                val fromA = extension(storeA, versionCode = 2, sources = mutableSources)
                val fromB = extension(storeB, versionCode = 3, sources = listOf(source(102, "Store B Source")))

                ExtensionsList.reconcileExtensionDatabase(listOf(fromA, fromB), listOf(storeA, storeB))
                mutableSources.clear()

                val activeVersion =
                    transaction {
                        ExtensionTable
                            .selectAll()
                            .where { ExtensionTable.pkgName eq TARGET_PKG }
                            .single()[ExtensionTable.versionCode]
                    }
                assertEquals(1, activeVersion, "reconciliation must not overwrite the active installed binary identity")

                val snapshotA = ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_A)
                assertTrue(snapshotA.ledgerEntryID > 0)
                assertEquals(2, snapshotA.expected.versionCode)
                assertEquals("a".repeat(64), snapshotA.expectedSigningKey)
                assertEquals(listOf(101L), snapshotA.expected.sources.map { it.id })
                assertEquals(TARGET_PKG, snapshotA.occupiedSourceOwners[101])
                assertEquals(OTHER_PKG, snapshotA.occupiedSourceOwners[777])
                assertFalse(0L in snapshotA.occupiedSourceOwners)

                val snapshotB = ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_B)
                assertEquals(3, snapshotB.expected.versionCode)
                assertEquals("b".repeat(64), snapshotB.expectedSigningKey)
                assertEquals(listOf(102L), snapshotB.expected.sources.map { it.id })

                transaction {
                    ExtensionStoreTable.update({ ExtensionStoreTable.indexUrl eq STORE_A }) {
                        it[signingKey] = "c".repeat(64)
                    }
                }
                val snapshotAfterUnrelatedStoreUpdate = ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_A)
                assertEquals("a".repeat(64), snapshotAfterUnrelatedStoreUpdate.expectedSigningKey)
                assertEquals(
                    snapshotA.storeSnapshotToken,
                    snapshotAfterUnrelatedStoreUpdate.storeSnapshotToken,
                    "an unrelated store-row update must not rewrite the captured entry generation",
                )
            }
        }

    @Test
    fun `partial refresh replaces only successful store partition and rolls back invalid replacement`() =
        runBlocking {
            withFixtureDatabase {
                val storeA = store(STORE_A, "a".repeat(64))
                val storeB = store(STORE_B, "b".repeat(64))
                insertStore(storeA)
                insertStore(storeB)

                ExtensionsList.reconcileExtensionDatabase(
                    listOf(
                        extension(storeA, 2, listOf(source(201, "A2"))),
                        extension(storeB, 3, listOf(source(301, "B3"))),
                        extension(storeB, 8, listOf(source(801, "B only")), B_ONLY_PKG),
                    ),
                    listOf(storeA, storeB),
                )

                val refreshedA = storeA.copy(signingKey = "d".repeat(64))
                transaction {
                    ExtensionStoreTable.update({ ExtensionStoreTable.indexUrl eq STORE_A }) {
                        it[signingKey] = refreshedA.signingKey
                    }
                }
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(refreshedA, 4, listOf(source(202, "A4")))),
                    listOf(refreshedA),
                )

                val refreshedSnapshotA = ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_A)
                assertEquals(4, refreshedSnapshotA.expected.versionCode)
                assertFalse(refreshedSnapshotA.storeSnapshotToken.isBlank())
                assertEquals(3, ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_B).expected.versionCode)
                assertEquals(8, ExtensionJarPreflightInputRepository.read(B_ONLY_PKG, STORE_B).expected.versionCode)
                val bOnlyStillPublished =
                    transaction {
                        ExtensionTable
                            .selectAll()
                            .where { ExtensionTable.pkgName eq B_ONLY_PKG }
                            .single()[ExtensionTable.isObsolete]
                    }
                assertFalse(bOnlyStillPublished, "a failed or skipped store partition must not be obsoleted")

                val duplicateSources = listOf(source(999, "duplicate"), source(999, "duplicate"))
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        ExtensionsList.reconcileExtensionDatabase(
                            listOf(extension(refreshedA, 5, duplicateSources)),
                            listOf(refreshedA),
                        )
                    }
                }
                assertEquals(
                    4,
                    ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_A).expected.versionCode,
                    "validation failure must leave the previous durable partition intact",
                )

                transaction {
                    ExtensionStoreTable.deleteWhere { ExtensionStoreTable.indexUrl eq STORE_B }
                }
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(refreshedA, 4, listOf(source(202, "A4")))),
                    listOf(refreshedA),
                )
                assertThrows(NoSuchElementException::class.java) {
                    ExtensionJarPreflightInputRepository.read(B_ONLY_PKG, STORE_B)
                }
                val removedStoreRows =
                    transaction {
                        ExtensionStoreEntryTable
                            .selectAll()
                            .where { ExtensionStoreEntryTable.storeIndexUrl eq STORE_B }
                            .count() to
                            ExtensionTable
                                .selectAll()
                                .where { ExtensionTable.pkgName eq B_ONLY_PKG }
                                .count()
                    }
                assertEquals(0L to 0L, removedStoreRows)
            }
        }

    private suspend fun withFixtureDatabase(block: suspend () -> Unit) {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase =
            Database.connect(
                "jdbc:h2:mem:artifact_ledger_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                    },
            )
        ExtensionsList.updateMap.clear()
        try {
            runMigrations(
                loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java),
                fixtureDatabase,
            )
            TransactionManager.defaultDatabase = fixtureDatabase
            block()
        } finally {
            ExtensionsList.updateMap.clear()
            TransactionManager.closeAndUnregister(fixtureDatabase)
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }

    private fun insertStore(store: ExtensionStore) {
        transaction {
            ExtensionStoreTable.insert {
                it[indexUrl] = store.indexUrl
                it[name] = store.name
                it[badgeLabel] = store.badgeLabel
                it[signingKey] = store.signingKey
                it[contactWebsite] = store.contact.website
                it[contactDiscord] = store.contact.discord
                it[isLegacy] = store.isLegacy
                it[extensionListUrl] = store.extensionListUrl
            }
        }
    }

    private fun insertInstalledExtension(
        packageName: String,
        versionCode: Long,
        sourceID: Long,
    ) {
        transaction {
            val extensionID =
                ExtensionTable.insertAndGetId {
                    it[apkName] = "$packageName.apk"
                    it[name] = packageName
                    it[pkgName] = packageName
                    it[versionName] = "1.4.$versionCode"
                    it[ExtensionTable.versionCode] = versionCode
                    it[lang] = "en"
                    it[extensionLib] = "1.4"
                    it[contentWarning] = ContentWarning.SAFE.ordinal
                    it[isInstalled] = true
                }
            SourceTable.insert {
                it[id] = sourceID
                it[name] = "Installed $sourceID"
                it[lang] = "en"
                it[extension] = extensionID
                it[contentWarning] = ContentWarning.SAFE.ordinal
            }
        }
    }

    private fun store(
        url: String,
        key: String,
    ) = ExtensionStore(
        indexUrl = url,
        name = url,
        badgeLabel = "fixture",
        signingKey = key,
        contact = ExtensionStore.Contact("https://contact.invalid", null),
        isLegacy = false,
        extensionListUrl = "$url/extensions.pb",
    )

    private fun extension(
        store: ExtensionStore,
        versionCode: Long,
        sources: List<ExtensionSource>,
        packageName: String = TARGET_PKG,
    ) = ExtensionInfo(
        storeIndexUrl = store.indexUrl,
        name = "Fixture extension",
        pkgName = packageName,
        apkUrl = "https://cdn.invalid/$versionCode.apk",
        jarUrl = "https://cdn.invalid/$versionCode.jar",
        iconUrl = "https://cdn.invalid/icon.png",
        extensionLib = "1.4",
        versionCode = versionCode,
        versionName = "1.4.$versionCode",
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        sources = sources,
    )

    private fun source(
        id: Long,
        name: String,
    ) = ExtensionSource(
        id = id,
        name = name,
        lang = "en",
        homeUrl = "https://source.invalid/$id",
        message = null,
        contentWarning = ContentWarning.SAFE,
    )

    private companion object {
        const val STORE_A = "https://store-a.invalid/index.pb"
        const val STORE_B = "https://store-b.invalid/index.pb"
        const val TARGET_PKG = "app.komu.fixture.target"
        const val OTHER_PKG = "app.komu.fixture.other"
        const val B_ONLY_PKG = "app.komu.fixture.storeb"
    }
}
