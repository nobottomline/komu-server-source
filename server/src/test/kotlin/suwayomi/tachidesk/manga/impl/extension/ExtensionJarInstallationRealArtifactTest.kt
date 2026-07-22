package suwayomi.tachidesk.manga.impl.extension

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import suwayomi.tachidesk.manga.model.table.ExtensionActivationMarkerTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ExtensionJarInstallationRealArtifactTest : ApplicationTest() {
    @TempDir
    lateinit var extensionsRoot: Path

    @Test
    fun `pinned signed extension installs reloads and reinstalls through canonical coordinator`() =
        runBlocking {
            val fixture = PinnedKeiyoushiRealArtifact.load()
            withFixtureDatabase {
                seedStore(fixture)
                val transport = CopyingPinnedTransport(fixture)
                val coordinator = coordinator(transport)
                val active = extensionsRoot.resolve("${PinnedKeiyoushiRealArtifact.PACKAGE_NAME}.jar")
                val loaderKey = active.toAbsolutePath().normalize().toString()

                GetSource.unregisterSource(PinnedKeiyoushiRealArtifact.SOURCE_ID)
                PackageTools.closeExtensionClassLoader(active)
                try {
                    val installed =
                        coordinator.execute(
                            PinnedKeiyoushiRealArtifact.PACKAGE_NAME,
                            ExtensionJarInstallationCoordinator.Intent.INSTALL,
                        )

                    assertCommittedArtifact(active)
                    assertCommittedDatabase()
                    assertEquals(listOf(PinnedKeiyoushiRealArtifact.SOURCE_ID), installed.metadata.installedSourceIDs)
                    assertTrue(installed.postCommit.isComplete)
                    assertEquals(1, installed.postCommit.attemptedArtifacts)
                    assertEquals(1, installed.postCommit.attemptedSources)
                    assertNoRecoveryResidue()
                    assertFalse(Files.exists(transport.lastStagedPath))

                    val className = installedClassName()
                    val firstSource = loadPinnedSource(active, className)
                    val firstLoader = checkNotNull(PackageTools.jarLoaderMap[loaderKey])
                    assertSourceIdentity(firstSource)

                    val reinstalled =
                        coordinator.execute(
                            PinnedKeiyoushiRealArtifact.PACKAGE_NAME,
                            ExtensionJarInstallationCoordinator.Intent.REINSTALL,
                        )

                    assertCommittedArtifact(active)
                    assertCommittedDatabase()
                    assertTrue(reinstalled.postCommit.isComplete)
                    assertEquals(1, reinstalled.postCommit.attemptedArtifacts)
                    assertEquals(1, reinstalled.postCommit.attemptedSources)
                    assertFalse(PackageTools.jarLoaderMap.containsKey(loaderKey))
                    assertNoRecoveryResidue()
                    assertFalse(Files.exists(transport.lastStagedPath))

                    val secondSource = loadPinnedSource(active, installedClassName())
                    val secondLoader = checkNotNull(PackageTools.jarLoaderMap[loaderKey])
                    assertSourceIdentity(secondSource)
                    assertNotSame(firstLoader, secondLoader)
                    assertNotSame(firstSource, secondSource)
                    assertEquals(2, transport.downloadCount)
                } finally {
                    PackageTools.closeExtensionClassLoader(active)
                    GetSource.unregisterSource(PinnedKeiyoushiRealArtifact.SOURCE_ID)
                }
            }
        }

    @Test
    fun `dedicated HTTPS transport downloads immutable pinned artifact into full preflight`() =
        runBlocking {
            assumeTrue(
                System.getenv(PinnedKeiyoushiRealArtifact.NETWORK_ENVIRONMENT) == "1",
                "Pinned Keiyoushi network fixture is not enabled",
            )
            val fixture = PinnedKeiyoushiRealArtifact.load()
            val candidate =
                ExtensionJarPreflightPipeline(DesktopExtensionArtifactTransport()).prepare(
                    expected = fixture.expected,
                    expectedSigningKey = PinnedKeiyoushiRealArtifact.SIGNING_KEY,
                    stagingDirectory = extensionsRoot,
                    occupiedSourceOwners = emptyMap(),
                )

            candidate.use {
                assertEquals(PinnedKeiyoushiRealArtifact.PINNED_JAR_URL, candidate.finalUrl)
                assertEquals(PinnedKeiyoushiRealArtifact.JAR_SIZE, candidate.size)
                assertEquals(PinnedKeiyoushiRealArtifact.JAR_SHA256, candidate.sha256)
                assertEquals(PinnedKeiyoushiRealArtifact.SIGNING_KEY, candidate.metadata.signature.signerFingerprint)
                assertEquals(listOf(PinnedKeiyoushiRealArtifact.SOURCE_ID), candidate.sources.map { it.id })
                candidate.assertIntegrity()
            }
            assertTrue(Files.list(extensionsRoot).use { it.toList() }.isEmpty())
        }

    private fun coordinator(transport: CopyingPinnedTransport) =
        ExtensionJarInstallationCoordinator(
            extensionsRoot = extensionsRoot,
            databaseIdentity = DATABASE_IDENTITY,
            preflightCoordinator = ExtensionJarPreflightCoordinator(transportSelector = { transport }),
        )

    private fun assertCommittedArtifact(active: Path) {
        assertTrue(Files.isRegularFile(active))
        assertEquals(PinnedKeiyoushiRealArtifact.JAR_SIZE, Files.size(active))
        assertEquals(
            PinnedKeiyoushiRealArtifact.JAR_SHA256,
            StagedArtifact.sha256(active, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES),
        )
    }

    private fun assertCommittedDatabase() {
        transaction {
            val extension =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName eq PinnedKeiyoushiRealArtifact.PACKAGE_NAME }
                    .single()
            assertTrue(extension[ExtensionTable.isInstalled])
            assertFalse(extension[ExtensionTable.isCustomPinned])
            assertEquals(PinnedKeiyoushiRealArtifact.VERSION_CODE, extension[ExtensionTable.versionCode])
            assertEquals(PinnedKeiyoushiRealArtifact.VERSION_NAME, extension[ExtensionTable.versionName])
            assertEquals(PinnedKeiyoushiRealArtifact.PINNED_INDEX_URL, extension[ExtensionTable.storeIndexUrl])
            assertEquals(PinnedKeiyoushiRealArtifact.PINNED_JAR_URL, extension[ExtensionTable.jarUrl])
            assertEquals(
                "${PinnedKeiyoushiRealArtifact.PACKAGE_NAME}.apk",
                extension[ExtensionTable.apkName],
            )
            assertEquals(
                listOf(PinnedKeiyoushiRealArtifact.SOURCE_ID),
                SourceTable
                    .selectAll()
                    .where { SourceTable.extension eq extension[ExtensionTable.id] }
                    .map { it[SourceTable.id].value },
            )
        }
    }

    private fun installedClassName(): String =
        transaction {
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.pkgName eq PinnedKeiyoushiRealArtifact.PACKAGE_NAME }
                .single()[ExtensionTable.classFQName]
        }

    private fun loadPinnedSource(
        active: Path,
        className: String,
    ): CatalogueSource {
        val main = PackageTools.loadExtensionSources(active, className)
        val sources: List<Source> =
            when (main) {
                is Source -> listOf(main)
                is SourceFactory -> main.createSources()
                else -> error("Pinned extension main class is neither Source nor SourceFactory")
            }
        return sources.single { it.id == PinnedKeiyoushiRealArtifact.SOURCE_ID } as CatalogueSource
    }

    private fun assertSourceIdentity(source: CatalogueSource) {
        assertEquals(PinnedKeiyoushiRealArtifact.SOURCE_ID, source.id)
        assertEquals("AHottie", source.name)
        assertEquals("all", source.lang)
        assertTrue(source is HttpSource)
        assertEquals(PinnedKeiyoushiRealArtifact.SOURCE_HOME_URL, (source as HttpSource).baseUrl)
    }

    private fun assertNoRecoveryResidue() {
        assertTrue(ExtensionActivationJournal(extensionsRoot).loadAll().isEmpty())
        assertTrue(
            transaction {
                ExtensionActivationMarkerTable.selectAll().none()
            },
        )
    }

    private suspend fun seedStore(fixture: PinnedKeiyoushiRealArtifact.Fixture) {
        transaction {
            insertStore(fixture.store)
        }
        ExtensionsList.reconcileExtensionDatabase(listOf(fixture.expected), listOf(fixture.store))
    }

    private fun insertStore(store: ExtensionStore) {
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

    private class CopyingPinnedTransport(
        private val fixture: PinnedKeiyoushiRealArtifact.Fixture,
    ) : ExtensionArtifactTransport {
        lateinit var lastStagedPath: Path
            private set
        var downloadCount: Int = 0
            private set

        override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact {
            check(request.url == PinnedKeiyoushiRealArtifact.PINNED_JAR_URL)
            check(request.packageName == PinnedKeiyoushiRealArtifact.PACKAGE_NAME)
            check(request.kind == ArtifactKind.JAR)
            downloadCount++
            lastStagedPath = Files.copy(fixture.jar, request.stagingDirectory.resolve("pinned-${UUID.randomUUID()}.jar"))
            return StagedArtifact(
                path = lastStagedPath,
                size = Files.size(lastStagedPath),
                sha256 = StagedArtifact.sha256(lastStagedPath, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES),
                finalUrl = request.url,
                kind = request.kind,
            )
        }
    }

    private suspend fun withFixtureDatabase(block: suspend () -> Unit) {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase =
            Database.connect(
                "jdbc:h2:mem:jar_install_real_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
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

    private companion object {
        const val DATABASE_IDENTITY = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
