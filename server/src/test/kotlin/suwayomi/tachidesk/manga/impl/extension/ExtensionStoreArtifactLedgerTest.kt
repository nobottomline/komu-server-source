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
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import suwayomi.tachidesk.manga.model.table.ExtensionActivationMarkerTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreEntryTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ExtensionStoreArtifactLedgerTest : ApplicationTest() {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `unchanged refresh retains exact trust generation even when a sibling extension changes`() =
        runBlocking {
            withFixtureDatabase {
                val store = store(STORE_A, "a".repeat(64))
                insertStore(store)
                val target = extension(store, 2, listOf(source(201, "Target")))
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(target, extension(store, 2, listOf(source(301, "Sibling")), B_ONLY_PKG)),
                    listOf(store),
                )
                val before = ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(target, extension(store, 3, listOf(source(301, "Sibling")), B_ONLY_PKG)),
                    listOf(store),
                )
                val after = ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
                assertEquals(before.ledgerEntryID, after.ledgerEntryID)
                assertEquals(before.storeSnapshotToken, after.storeSnapshotToken)
                ExtensionJarPreflightInputRepository.verifyStillCurrent(before, setOf(201))
                ExtensionsList.reconcileExtensionDatabase(listOf(target), listOf(store.copy(signingKey = "b".repeat(64))))
                assertThrows(StaleExtensionStoreSnapshotException::class.java) {
                    ExtensionJarPreflightInputRepository.verifyStillCurrent(before, setOf(201))
                }
            }
        }

    @Test
    fun `durable update survives lost process projection and completed retries are no ops`() =
        runBlocking {
            withFixtureDatabase {
                val store = store(STORE_A, "a".repeat(64))
                insertStore(store)
                insertInstalledExtension(TARGET_PKG, 1, 101)
                ExtensionsList.reconcileExtensionDatabase(listOf(extension(store, 2, listOf(source(101, "Target")))), listOf(store))
                ExtensionsList.updateMap.clear()
                assertTrue(Extension.hasPublishedUpdate(TARGET_PKG))
                transaction {
                    ExtensionTable.update({ ExtensionTable.pkgName eq TARGET_PKG }) {
                        it[versionCode] = 2
                        it[hasUpdate] = false
                    }
                }
                assertFalse(Extension.hasPublishedUpdate(TARGET_PKG))
                assertEquals(TARGET_PKG, Extension.updateExtension(TARGET_PKG))
                transaction {
                    ExtensionTable.update({ ExtensionTable.pkgName eq TARGET_PKG }) { it[isCustomPinned] = true }
                }
                assertThrows(CustomPinnedExtensionUpdateException::class.java) { Extension.hasPublishedUpdate(TARGET_PKG) }
                assertThrows(NoSuchElementException::class.java) { Extension.hasPublishedUpdate("absent.extension") }
            }
        }

    @Test
    fun `APK only legacy update availability does not require a JAR signer or positive source identity`() =
        runBlocking {
            withFixtureDatabase {
                val store = store(STORE_A, "").copy(isLegacy = true)
                insertStore(store)
                insertInstalledExtension(TARGET_PKG, 1, 101)
                val candidate = extension(store, 2, listOf(source(0, "Legacy"))).copy(jarUrl = null)
                ExtensionsList.reconcileExtensionDatabase(listOf(candidate), listOf(store))
                ExtensionsList.updateMap.clear()
                assertTrue(Extension.hasPublishedUpdate(TARGET_PKG))
                assertEquals(2, ExtensionStoreArtifactLedger.readUpdateCandidate(TARGET_PKG).versionCode)
            }
        }

    @Test
    fun `automatic direct jar ownership fails closed for duplicate packages across stores`() =
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
                    ),
                    listOf(storeA, storeB),
                )

                val failure =
                    assertThrows(AmbiguousExtensionStoreOwnershipException::class.java) {
                        ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
                    }
                assertTrue(failure.message.orEmpty().contains("2 stores"))
                assertEquals(2, ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_A).expected.versionCode)
                assertEquals(3, ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_B).expected.versionCode)
            }
        }

    @Test
    fun `postflight fence rejects stale generations and newly occupied source ids`() =
        runBlocking {
            withFixtureDatabase {
                val storeA = store(STORE_A, "a".repeat(64))
                insertStore(storeA)
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(storeA, 2, listOf(source(201, "A2")))),
                    listOf(storeA),
                )

                val snapshot = ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
                ExtensionJarPreflightInputRepository.verifyStillCurrent(snapshot, setOf(201))

                insertInstalledExtension(OTHER_PKG, versionCode = 1, sourceID = 201)
                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarPreflightInputRepository.verifyStillCurrent(snapshot, setOf(201))
                }

                transaction {
                    SourceTable.deleteWhere { SourceTable.id eq 201L }
                }
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(storeA, 3, listOf(source(301, "A3")))),
                    listOf(storeA),
                )
                assertThrows(StaleExtensionStoreSnapshotException::class.java) {
                    ExtensionJarPreflightInputRepository.verifyStillCurrent(snapshot, setOf(201))
                }
            }
        }

    @Test
    fun `postflight fence rejects ownership that becomes ambiguous while jar is inspected`() =
        runBlocking {
            withFixtureDatabase {
                val storeA = store(STORE_A, "a".repeat(64))
                val storeB = store(STORE_B, "b".repeat(64))
                insertStore(storeA)
                insertStore(storeB)
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(storeA, 2, listOf(source(201, "A2")))),
                    listOf(storeA),
                )

                val snapshot = ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(storeB, 3, listOf(source(301, "B3")))),
                    listOf(storeB),
                )

                assertThrows(AmbiguousExtensionStoreOwnershipException::class.java) {
                    ExtensionJarPreflightInputRepository.verifyStillCurrent(snapshot, setOf(201))
                }
            }
        }

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

    @Test
    fun `total refresh failure preserves the last catalogue and installed update candidates`() =
        runBlocking {
            withFixtureDatabase {
                val storeA = store(STORE_A, "a".repeat(64))
                insertStore(storeA)
                insertInstalledExtension(TARGET_PKG, versionCode = 1, sourceID = 101)
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(
                        extension(storeA, 2, listOf(source(101, "Installed source"))),
                        extension(storeA, 3, listOf(source(301, "Available source")), B_ONLY_PKG),
                    ),
                    listOf(storeA),
                )
                val catalogue = ExtensionsList.extensionTableAsDataClass().sortedBy { it.pkgName }
                val updateCandidates = ExtensionsList.updateMap.toMap()
                val snapshot = ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)

                ExtensionsList.reconcileExtensionDatabase(emptyList(), emptyList())

                assertEquals(catalogue, ExtensionsList.extensionTableAsDataClass().sortedBy { it.pkgName })
                assertEquals(updateCandidates, ExtensionsList.updateMap.toMap())
                val retainedSnapshot = ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
                assertEquals(snapshot.ledgerEntryID, retainedSnapshot.ledgerEntryID)
                assertEquals(snapshot.storeSnapshotToken, retainedSnapshot.storeSnapshotToken)
                assertEquals(snapshot.expectedSigningKey, retainedSnapshot.expectedSigningKey)
                assertEquals(snapshot.expected, retainedSnapshot.expected)
            }
        }

    @Test
    fun `total refresh failure preserves configured rows before their first successful ledger snapshot`() =
        runBlocking {
            withFixtureDatabase {
                val storeA = store(STORE_A, "a".repeat(64))
                insertStore(storeA)
                insertInstalledExtension(TARGET_PKG, versionCode = 1, sourceID = 101)
                // Released databases can have catalogue rows before the ledger has fetched
                // this store. Failed network requests cannot make those rows authoritative.
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(
                        extension(storeA, 2, listOf(source(101, "Installed source"))),
                        extension(storeA, 3, listOf(source(301, "Available source")), B_ONLY_PKG),
                    ),
                )
                val catalogue = ExtensionsList.extensionTableAsDataClass().sortedBy { it.pkgName }
                val updateCandidates = ExtensionsList.updateMap.toMap()

                ExtensionsList.reconcileExtensionDatabase(emptyList(), emptyList())

                assertEquals(catalogue, ExtensionsList.extensionTableAsDataClass().sortedBy { it.pkgName })
                assertEquals(updateCandidates, ExtensionsList.updateMap.toMap())
            }
        }

    @Test
    fun `a successfully refreshed empty store removes its catalogue but retains installed runtime`() =
        runBlocking {
            withFixtureDatabase {
                val storeA = prepareInstalledAndAvailableCatalogue()

                ExtensionsList.reconcileExtensionDatabase(emptyList(), listOf(storeA))

                assertOnlyObsoleteInstalledRuntimeRemains()
                assertThrows(NoSuchElementException::class.java) {
                    ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_A)
                }
            }
        }

    @Test
    fun `removing the final store still retires its catalogue during total network failure`() =
        runBlocking {
            withFixtureDatabase {
                prepareInstalledAndAvailableCatalogue()
                transaction {
                    ExtensionStoreTable.deleteWhere { ExtensionStoreTable.indexUrl eq STORE_A }
                }

                ExtensionsList.reconcileExtensionDatabase(emptyList(), emptyList())

                assertOnlyObsoleteInstalledRuntimeRemains()
                assertThrows(NoSuchElementException::class.java) {
                    ExtensionJarPreflightInputRepository.read(TARGET_PKG, STORE_A)
                }
            }
        }

    private suspend fun prepareInstalledAndAvailableCatalogue(): ExtensionStore {
        val storeA = store(STORE_A, "a".repeat(64))
        insertStore(storeA)
        insertInstalledExtension(TARGET_PKG, versionCode = 1, sourceID = 101)
        ExtensionsList.reconcileExtensionDatabase(
            listOf(
                extension(storeA, 2, listOf(source(101, "Installed source"))),
                extension(storeA, 3, listOf(source(301, "Available source")), B_ONLY_PKG),
            ),
            listOf(storeA),
        )
        return storeA
    }

    private fun assertOnlyObsoleteInstalledRuntimeRemains() {
        val remaining = ExtensionsList.extensionTableAsDataClass().single()
        assertEquals(TARGET_PKG, remaining.pkgName)
        assertEquals(1L, remaining.versionCode)
        assertEquals("$TARGET_PKG.apk", remaining.apkName)
        assertTrue(remaining.installed)
        assertTrue(remaining.obsolete)
        assertFalse(remaining.hasUpdate)
        assertTrue(ExtensionsList.updateMap.isEmpty())
        val source =
            transaction {
                SourceTable.selectAll().where { SourceTable.id eq 101L }.single()[SourceTable.name]
            }
        assertEquals("Installed 101", source)
    }

    @Test
    fun `publish authorization atomically commits metadata with the expected marker`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val expectedMarker = publication.entry.record.markerKey

                val result =
                    ExtensionJarPublishAuthorization.commit(publication.entry) { markerKey ->
                        ExtensionActivationMarkerTable.insert {
                            it[ExtensionActivationMarkerTable.markerKey] = markerKey
                            it[packageName] = TARGET_PKG
                        }
                        "committed"
                    }

                assertEquals("committed", result)
                assertTrue(markerPresent(expectedMarker))
            }
        }

    @Test
    fun `publish authorization requires one visible authenticated journal operation`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val preparedEntry =
                    publication.entry.copy(
                        record = publication.entry.record.copy(state = ExtensionActivationJournal.State.PREPARED),
                    )
                val legacyEntry =
                    publication.entry.copy(
                        record = publication.entry.record.copy(storeTrustBinding = null),
                    )

                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarPublishAuthorization.commit(preparedEntry) { error("callback must not run") }
                }
                assertThrows(SecurityException::class.java) {
                    ExtensionJarPublishAuthorization.commit(legacyEntry) { error("callback must not run") }
                }
            }
        }

    @Test
    fun `publish authorization rejects a visible journal that lost its package lease`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val lease =
                    Files.list(publication.entry.path.parent).use { paths ->
                        paths
                            .filter { it.fileName.toString().endsWith(".lock") }
                            .findFirst()
                            .orElseThrow()
                    }
                Files.delete(lease)
                var callbackCalled = false

                assertThrows(IllegalArgumentException::class.java) {
                    ExtensionJarPublishAuthorization.commit(publication.entry) {
                        callbackCalled = true
                    }
                }
                assertFalse(callbackCalled)
            }
        }

    @Test
    fun `publish authorization rolls back callback writes when expected marker is missing`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val expectedMarker = publication.entry.record.markerKey

                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarPublishAuthorization.commit(publication.entry) {
                        ExtensionActivationMarkerTable.insert {
                            it[markerKey] = OTHER_MARKER_KEY
                            it[packageName] = TARGET_PKG
                        }
                    }
                }

                assertFalse(markerPresent(expectedMarker))
                assertFalse(markerPresent(OTHER_MARKER_KEY), "the entire metadata callback transaction must roll back")
            }
        }

    @Test
    fun `publish authorization rejects an expected marker owned by another package`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val expectedMarker = publication.entry.record.markerKey

                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarPublishAuthorization.commit(publication.entry) { markerKey ->
                        ExtensionActivationMarkerTable.insert {
                            it[ExtensionActivationMarkerTable.markerKey] = markerKey
                            it[packageName] = OTHER_PKG
                        }
                    }
                }

                assertFalse(markerPresent(expectedMarker), "a marker for the wrong package must be rolled back")
            }
        }

    @Test
    fun `publish authorization rejects a stale store generation before metadata callback`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val expectedMarker = publication.entry.record.markerKey
                val storeA = store(STORE_A, "a".repeat(64))
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(storeA, 3, listOf(source(301, "A3")))),
                    listOf(storeA),
                )
                var callbackCalled = false

                assertThrows(StaleExtensionStoreSnapshotException::class.java) {
                    ExtensionJarPublishAuthorization.commit(publication.entry) {
                        callbackCalled = true
                    }
                }

                assertFalse(callbackCalled)
                assertFalse(markerPresent(expectedMarker))
            }
        }

    @Test
    fun `publish authorization rejects newly occupied source ids`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val expectedMarker = publication.entry.record.markerKey
                insertInstalledExtension(OTHER_PKG, versionCode = 1, sourceID = 201)

                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarPublishAuthorization.commit(publication.entry) { markerKey ->
                        ExtensionActivationMarkerTable.insert {
                            it[ExtensionActivationMarkerTable.markerKey] = markerKey
                            it[packageName] = TARGET_PKG
                        }
                    }
                }

                assertFalse(markerPresent(expectedMarker))
            }
        }

    @Test
    fun `publish authorization rolls back metadata if active bytes change during callback`() =
        runBlocking {
            withFixtureDatabase {
                val snapshot = prepareUniqueStoreSnapshot()
                val publication = prepareVisiblePublication(snapshot, "authenticated candidate")
                val expectedMarker = publication.entry.record.markerKey

                assertThrows(IllegalArgumentException::class.java) {
                    ExtensionJarPublishAuthorization.commit(publication.entry) { markerKey ->
                        ExtensionActivationMarkerTable.insert {
                            it[ExtensionActivationMarkerTable.markerKey] = markerKey
                            it[packageName] = TARGET_PKG
                        }
                        Files.writeString(publication.activeArtifact, "tampered")
                    }
                }

                assertFalse(markerPresent(expectedMarker), "integrity failure must roll back the metadata marker")
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

    private suspend fun prepareUniqueStoreSnapshot(): ExtensionJarPreflightInputSnapshot {
        val storeA = store(STORE_A, "a".repeat(64))
        insertStore(storeA)
        ExtensionsList.reconcileExtensionDatabase(
            listOf(extension(storeA, 2, listOf(source(201, "A2")))),
            listOf(storeA),
        )
        return ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
    }

    private fun prepareVisiblePublication(
        snapshot: ExtensionJarPreflightInputSnapshot,
        content: String,
    ): VisiblePublication {
        val extensionsRoot = temporaryDirectory.resolve(UUID.randomUUID().toString())
        Files.createDirectories(extensionsRoot)
        val stagedArtifact = Files.writeString(extensionsRoot.resolve("candidate.jar"), content)
        val activeArtifact = extensionsRoot.resolve("active.jar")
        val binding = authenticatedBinding(snapshot, stagedArtifact)
        val journal = ExtensionActivationJournal(extensionsRoot)
        var entry =
            journal.prepareAuthenticated(
                packageName = snapshot.expected.pkgName,
                databaseIdentity = "c".repeat(64),
                stagedArtifact = stagedArtifact,
                activeArtifact = activeArtifact,
                previousArtifact = null,
                storeTrustBinding = binding,
            )
        entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
        journal.publish(entry)
        entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_VISIBLE)
        return VisiblePublication(entry, activeArtifact)
    }

    private fun authenticatedBinding(
        snapshot: ExtensionJarPreflightInputSnapshot,
        activeArtifact: Path,
    ) = ExtensionActivationJournal.AuthenticatedStoreBinding(
        packageName = snapshot.expected.pkgName,
        ledgerEntryID = snapshot.ledgerEntryID,
        storeSnapshotToken = snapshot.storeSnapshotToken,
        storeIndexUrl = snapshot.expected.storeIndexUrl,
        storeSigningKey = snapshot.expectedSigningKey,
        candidateSize = Files.size(activeArtifact),
        candidateSha256 = StagedArtifact.sha256(activeArtifact, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES),
        sourceIDs =
            snapshot.expected.sources
                .map { it.id }
                .sorted(),
    )

    private fun markerPresent(markerKey: String): Boolean =
        transaction {
            ExtensionActivationMarkerTable
                .selectAll()
                .where { ExtensionActivationMarkerTable.markerKey eq markerKey }
                .any()
        }

    private data class VisiblePublication(
        val entry: ExtensionActivationJournal.Entry,
        val activeArtifact: Path,
    )

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
        const val OTHER_MARKER_KEY = "komu.extension.activation.123e4567-e89b-12d3-a456-426614174002"
    }
}
