package suwayomi.tachidesk.manga.impl.extension

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.graphql.types.DatabaseType
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
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExtensionJarActivationOrchestratorTest : ApplicationTest() {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `authenticated candidate commits metadata then finalizes journal and marker`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation()

                val result =
                    ExtensionJarActivationOrchestrator.activate(
                        operation.candidate,
                        ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                    )

                assertTrue(result.extensionID > 0)
                assertEquals(listOf(SOURCE_ID), result.installedSourceIDs)
                assertEquals("new", Files.readString(operation.active))
                assertFalse(Files.exists(operation.staged))
                assertTrue(
                    operation.candidate.journal
                        .loadAll()
                        .isEmpty(),
                )
                assertFalse(markerPresent(operation.markerKey))
                assertTrue(extensionInstalled())
                assertEquals(listOf(SOURCE_ID), installedSourceIDs())
            }
        }

    @Test
    fun `metadata failure rolls back a freshly published candidate`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation()

                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarActivationOrchestrator.activate(operation.candidate) { metadata ->
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata,
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                        throw IllegalStateException("metadata rejected")
                    }
                }

                assertFalse(Files.exists(operation.active))
                assertFalse(Files.exists(operation.staged))
                assertTrue(
                    operation.candidate.journal
                        .loadAll()
                        .isEmpty(),
                )
                assertFalse(markerPresent(operation.markerKey))
                assertFalse(extensionInstalled(), "metadata writer changes must roll back with its transaction")
                assertTrue(installedSourceIDs().isEmpty())
            }
        }

    @Test
    fun `runtime hook failure after commit cannot roll back authenticated activation`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation()
                var hookCalled = false

                val result =
                    ExtensionJarActivationOrchestrator.activate(
                        operation.candidate,
                        ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                    ) {
                        hookCalled = true
                        assertTrue(markerPresent(operation.markerKey), "commit marker must exist before runtime effects")
                        assertEquals("new", Files.readString(operation.active))
                        assertEquals(
                            ExtensionActivationJournal.State.FILE_VISIBLE,
                            operation.candidate.journal
                                .loadAll()
                                .single()
                                .record.state,
                        )
                        throw IllegalStateException("runtime cleanup rejected")
                    }

                assertTrue(hookCalled)
                assertTrue(result.extensionID > 0)
                assertTrue(extensionInstalled())
                assertEquals("new", Files.readString(operation.active))
                assertTrue(
                    operation.candidate.journal
                        .loadAll()
                        .isEmpty(),
                )
                assertFalse(markerPresent(operation.markerKey))
            }
        }

    @Test
    fun `metadata failure restores same-path old-good artifact and rolls back marker`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation(previousAtActivePath = true)

                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarActivationOrchestrator.activate(operation.candidate) { metadata ->
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata,
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                        throw IllegalStateException("metadata rejected")
                    }
                }

                assertEquals("old", Files.readString(operation.active))
                assertFalse(Files.exists(operation.staged))
                assertTrue(
                    operation.candidate.journal
                        .loadAll()
                        .isEmpty(),
                )
                assertFalse(markerPresent(operation.markerKey))
            }
        }

    @Test
    fun `stale store generation is rejected before callback and old-good artifact is restored`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation(previousAtActivePath = true)
                val refreshedStore = store(SIGNING_KEY)
                ExtensionsList.reconcileExtensionDatabase(
                    listOf(extension(refreshedStore, versionCode = 3, sourceID = 301)),
                    listOf(refreshedStore),
                )
                var callbackCalled = false

                assertThrows(StaleExtensionStoreSnapshotException::class.java) {
                    ExtensionJarActivationOrchestrator.activate(operation.candidate) {
                        callbackCalled = true
                    }
                }

                assertFalse(callbackCalled)
                assertEquals("old", Files.readString(operation.active))
                assertTrue(
                    operation.candidate.journal
                        .loadAll()
                        .isEmpty(),
                )
                assertFalse(markerPresent(operation.markerKey))
            }
        }

    @Test
    fun `committed transaction survives cleanup failure and startup recovery finalizes it`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation()
                val lease = singleLease(operation.candidate.entry.path.parent)

                val result =
                    ExtensionJarActivationOrchestrator.activate(operation.candidate) { metadata ->
                        val writeResult =
                            ExtensionJarMetadataWriter.write(
                                this,
                                metadata,
                                ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                            )
                        Files.delete(lease)
                        writeResult
                    }

                assertTrue(result.extensionID > 0)
                assertEquals("new", Files.readString(operation.active))
                assertEquals(
                    ExtensionActivationJournal.State.FILE_VISIBLE,
                    operation.candidate.journal
                        .loadAll()
                        .single()
                        .record.state,
                )
                assertTrue(markerPresent(operation.markerKey))

                val report =
                    operation.candidate.journal.recoverAll(
                        DATABASE_IDENTITY,
                        { markerKey -> TARGET_PKG.takeIf { markerPresent(markerKey) } },
                        ::removeMarker,
                    )

                assertEquals(1, report.finalized)
                assertTrue(
                    operation.candidate.journal
                        .loadAll()
                        .isEmpty(),
                )
                assertFalse(markerPresent(operation.markerKey))
                assertEquals("new", Files.readString(operation.active))
                assertTrue(extensionInstalled())
            }
        }

    @Test
    fun `active-byte mutation during transaction fails closed and preserves recovery evidence`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation()

                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        ExtensionJarActivationOrchestrator.activate(operation.candidate) { metadata ->
                            ExtensionJarMetadataWriter.write(
                                this,
                                metadata,
                                ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                            )
                            Files.writeString(operation.active, "tampered")
                        }
                    }

                assertTrue(failure.suppressed.isNotEmpty(), "unsafe rollback refusal must remain attached")
                assertEquals("tampered", Files.readString(operation.active))
                assertEquals(
                    ExtensionActivationJournal.State.FILE_VISIBLE,
                    operation.candidate.journal
                        .loadAll()
                        .single()
                        .record.state,
                )
                assertFalse(markerPresent(operation.markerKey), "failed transaction marker must roll back")
                assertFalse(extensionInstalled())
                assertTrue(installedSourceIDs().isEmpty())
            }
        }

    @Test
    fun `mismatched typed handoff is rejected before publication and safely rolled back`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation()
                val candidate = operation.candidate
                val mismatched =
                    ExtensionJarPreflightCoordinator.JournaledCandidate(
                        journal = candidate.journal,
                        entry = candidate.entry,
                        expected = candidate.expected.copy(versionCode = candidate.expected.versionCode + 1),
                        metadata = candidate.metadata,
                        sources = candidate.sources,
                        extensionLanguage = candidate.extensionLanguage,
                        finalUrl = candidate.finalUrl,
                    )

                assertThrows(SecurityException::class.java) {
                    ExtensionJarActivationOrchestrator.activate(mismatched) { error("callback must not run") }
                }

                assertFalse(Files.exists(operation.active))
                assertFalse(Files.exists(operation.staged))
                assertTrue(
                    candidate.journal
                        .loadAll()
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `activation holds store generation stable until its serializable commit completes`() =
        runBlocking {
            withFixtureDatabase {
                val operation = prepareOperation()
                val activationInsideCommit = CountDownLatch(1)
                val allowActivationCommit = CountDownLatch(1)
                val refreshAttemptingMutation = CountDownLatch(1)
                val refreshCompleted = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)

                try {
                    val activation =
                        executor.submit<ExtensionJarMetadataWriter.Result> {
                            ExtensionJarActivationOrchestrator.activate(operation.candidate) { metadata ->
                                val result =
                                    ExtensionJarMetadataWriter.write(
                                        this,
                                        metadata,
                                        ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                                    )
                                activationInsideCommit.countDown()
                                check(allowActivationCommit.await(10, TimeUnit.SECONDS)) {
                                    "Timed out waiting to release authenticated activation"
                                }
                                result
                            }
                        }

                    assertTrue(
                        activationInsideCommit.await(10, TimeUnit.SECONDS),
                        "Activation did not enter its serializable commit",
                    )
                    val refresh =
                        executor.submit {
                            transaction {
                                refreshAttemptingMutation.countDown()
                                ExtensionStoreEntryTable.deleteWhere {
                                    ExtensionStoreEntryTable.pkgName eq TARGET_PKG
                                }
                            }
                            refreshCompleted.countDown()
                        }

                    assertTrue(
                        refreshAttemptingMutation.await(10, TimeUnit.SECONDS),
                        "Concurrent store refresh did not reach its mutation boundary",
                    )
                    assertFalse(
                        refreshCompleted.await(500, TimeUnit.MILLISECONDS),
                        "Store generation mutation escaped the activation trust-row lock",
                    )

                    allowActivationCommit.countDown()
                    val result = activation.get(10, TimeUnit.SECONDS)
                    refresh.get(10, TimeUnit.SECONDS)

                    assertTrue(result.extensionID > 0)
                    assertTrue(refreshCompleted.await(1, TimeUnit.SECONDS))
                    assertTrue(extensionInstalled())
                    assertEquals("new", Files.readString(operation.active))
                    assertFalse(markerPresent(operation.markerKey))
                } finally {
                    allowActivationCommit.countDown()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
                }
            }
        }

    private suspend fun prepareOperation(previousAtActivePath: Boolean = false): Operation {
        val store = store(SIGNING_KEY)
        insertStore(store)
        ExtensionsList.reconcileExtensionDatabase(
            listOf(extension(store, versionCode = 2, sourceID = SOURCE_ID)),
            listOf(store),
        )
        val snapshot = ExtensionJarPreflightInputRepository.readUniquelyOwned(TARGET_PKG)
        val root = temporaryDirectory.resolve(UUID.randomUUID().toString())
        Files.createDirectories(root)
        val staged = Files.writeString(root.resolve("candidate.staging.jar"), "new")
        val active = root.resolve("active.jar")
        val previous = active.takeIf { previousAtActivePath }?.also { Files.writeString(it, "old") }
        val digest = StagedArtifact.sha256(staged, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES)
        val metadata = artifactMetadata(snapshot)
        val validated =
            ExtensionJarPreflightPipeline.ValidatedCandidate(
                artifact =
                    StagedArtifact(
                        path = staged,
                        size = Files.size(staged),
                        sha256 = digest,
                        finalUrl = checkNotNull(snapshot.expected.jarUrl),
                        kind = ArtifactKind.JAR,
                    ),
                expected = snapshot.expected,
                metadata = metadata,
                sources =
                    snapshot.expected.sources.map {
                        ExtensionJarShadowValidator.SourceMetadata(it.id, it.name, it.lang)
                    },
                extensionLanguage = snapshot.expected.lang,
            )
        val prepared = ExtensionJarPreflightCoordinator.PreparedCandidate(snapshot, validated)
        val candidate = prepared.transferToJournal(DATABASE_IDENTITY, active, previous)
        prepared.close()
        return Operation(candidate, staged, active, candidate.entry.record.markerKey)
    }

    private fun artifactMetadata(snapshot: ExtensionJarPreflightInputSnapshot): ExtensionJarArtifactInspector.Metadata {
        val expected = snapshot.expected
        val className = "${expected.pkgName}.FixtureSource"
        return ExtensionJarArtifactInspector.Metadata(
            packageName = expected.pkgName,
            versionCode = expected.versionCode,
            versionName = expected.versionName,
            extensionLib = expected.extensionLib,
            name = expected.name,
            classFQName = className,
            contentWarning = expected.contentWarning,
            signature =
                ExtensionJarVerifier.Result(
                    signerFingerprint = snapshot.expectedSigningKey,
                    verifiedEntries = 1,
                    uncompressedBytes = 3,
                    verifiedEntryNames = setOf(className.replace('.', '/') + ".class"),
                ),
        )
    }

    private suspend fun withFixtureDatabase(block: suspend () -> Unit) {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val previousDatabaseType = serverConfig.databaseType.value
        val postgres = PostgresFixture.fromEnvironment()
        val fixtureDatabase = postgres?.connect() ?: connectH2Fixture()
        ExtensionsList.updateMap.clear()
        try {
            if (postgres != null) serverConfig.databaseType.value = DatabaseType.POSTGRESQL
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
            serverConfig.databaseType.value = previousDatabaseType
            postgres?.close()
        }
    }

    private fun connectH2Fixture(): Database =
        Database.connect(
            "jdbc:h2:mem:jar_activation_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig = fixtureDatabaseConfig(),
        )

    private fun fixtureDatabaseConfig(defaultSchema: Schema? = null): DatabaseConfig =
        DatabaseConfig {
            useNestedTransactions = true
            @OptIn(ExperimentalKeywordApi::class)
            preserveKeywordCasing = false
            this.defaultSchema = defaultSchema
        }

    private class PostgresFixture private constructor(
        private val jdbcUrl: String,
        private val username: String,
        private val password: String,
    ) : AutoCloseable {
        private val schema = "komu_jar_activation_${UUID.randomUUID().toString().replace("-", "")}"

        fun connect(): Database {
            DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA \"$schema\"")
                }
            }
            val separator = if ('?' in jdbcUrl) '&' else '?'
            return Database.connect(
                "$jdbcUrl${separator}currentSchema=$schema",
                driver = "org.postgresql.Driver",
                user = username,
                password = password,
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                        defaultSchema = Schema(schema)
                    },
            )
        }

        override fun close() {
            DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA \"$schema\" CASCADE")
                }
            }
        }

        companion object {
            fun fromEnvironment(): PostgresFixture? {
                val jdbcUrl = System.getenv(POSTGRES_JDBC_URL_ENV)?.takeIf { it.isNotBlank() } ?: return null
                require(jdbcUrl.startsWith("jdbc:postgresql://")) {
                    "$POSTGRES_JDBC_URL_ENV must use a PostgreSQL JDBC URL"
                }
                val username =
                    requireNotNull(System.getenv(POSTGRES_USERNAME_ENV)?.takeIf { it.isNotBlank() }) {
                        "$POSTGRES_USERNAME_ENV is required with $POSTGRES_JDBC_URL_ENV"
                    }
                return PostgresFixture(
                    jdbcUrl = jdbcUrl,
                    username = username,
                    password = System.getenv(POSTGRES_PASSWORD_ENV).orEmpty(),
                )
            }
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

    private fun markerPresent(markerKey: String): Boolean =
        transaction {
            ExtensionActivationMarkerTable
                .selectAll()
                .where { ExtensionActivationMarkerTable.markerKey eq markerKey }
                .any()
        }

    private fun extensionInstalled(): Boolean =
        transaction {
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.pkgName eq TARGET_PKG }
                .single()[ExtensionTable.isInstalled]
        }

    private fun installedSourceIDs(): List<Long> =
        transaction {
            val extensionID =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName eq TARGET_PKG }
                    .single()[ExtensionTable.id]
            SourceTable
                .selectAll()
                .where { SourceTable.extension eq extensionID }
                .map { it[SourceTable.id].value }
                .sorted()
        }

    private fun removeMarker(markerKey: String) {
        transaction {
            ExtensionActivationMarkerTable.deleteWhere { ExtensionActivationMarkerTable.markerKey eq markerKey }
        }
    }

    private fun singleLease(journalRoot: Path): Path =
        Files.list(journalRoot).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".lock") }
                .findFirst()
                .orElseThrow()
        }

    private fun store(signingKey: String) =
        ExtensionStore(
            indexUrl = STORE_URL,
            name = "Fixture store",
            badgeLabel = "fixture",
            signingKey = signingKey,
            contact = ExtensionStore.Contact("https://contact.invalid", null),
            isLegacy = false,
            extensionListUrl = "$STORE_URL/extensions.pb",
        )

    private fun extension(
        store: ExtensionStore,
        versionCode: Long,
        sourceID: Long,
    ) = ExtensionInfo(
        storeIndexUrl = store.indexUrl,
        name = "Fixture extension",
        pkgName = TARGET_PKG,
        apkUrl = "https://cdn.invalid/$versionCode.apk",
        jarUrl = "https://cdn.invalid/$versionCode.jar",
        iconUrl = "https://cdn.invalid/icon.png",
        extensionLib = "1.4",
        versionCode = versionCode,
        versionName = "1.4.$versionCode",
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        sources = listOf(source(sourceID)),
    )

    private fun source(id: Long) =
        ExtensionSource(
            id = id,
            name = "Fixture source $id",
            lang = "en",
            homeUrl = "https://source.invalid/$id",
            message = null,
            contentWarning = ContentWarning.SAFE,
        )

    private data class Operation(
        val candidate: ExtensionJarPreflightCoordinator.JournaledCandidate,
        val staged: Path,
        val active: Path,
        val markerKey: String,
    )

    private companion object {
        const val STORE_URL = "https://store.invalid/index.pb"
        const val SIGNING_KEY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATABASE_IDENTITY = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val TARGET_PKG = "app.komu.fixture.target"
        const val SOURCE_ID = 201L
        const val POSTGRES_JDBC_URL_ENV = "KOMU_TEST_POSTGRES_JDBC_URL"
        const val POSTGRES_USERNAME_ENV = "KOMU_TEST_POSTGRES_USERNAME"
        const val POSTGRES_PASSWORD_ENV = "KOMU_TEST_POSTGRES_PASSWORD"
    }
}
