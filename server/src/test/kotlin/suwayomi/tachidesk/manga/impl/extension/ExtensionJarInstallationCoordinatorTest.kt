package suwayomi.tachidesk.manga.impl.extension

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
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
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ExtensionJarInstallationCoordinatorTest : ApplicationTest() {
    @TempDir
    lateinit var extensionsRoot: Path

    @Test
    fun `same catalogue refreshed during download does not invalidate authenticated preflight`() =
        runBlocking {
            withFixtureDatabase {
                seedStore(2)
                val transport = FakeTransport { ExtensionsList.reconcileExtensionDatabase(listOf(extension(2)), listOf(store())) }
                coordinator(
                    transport,
                    committedEffects(mutableListOf()),
                ).execute(TARGET_PKG, ExtensionJarInstallationCoordinator.Intent.INSTALL)
                assertEquals(1, transport.downloadCount)
                assertCommitted(2, "$TARGET_PKG.apk", listOf(SOURCE_ID))
            }
        }

    @Test
    fun `changed catalogue during download retries from new authenticated metadata and cleans discarded staging`() =
        runBlocking {
            withFixtureDatabase {
                seedStore(2)
                var changed = false
                val transport =
                    FakeTransport {
                        if (!changed) {
                            changed = true
                            ExtensionsList.reconcileExtensionDatabase(listOf(extension(3)), listOf(store()))
                        }
                    }
                coordinator(
                    transport,
                    committedEffects(mutableListOf()),
                ).execute(TARGET_PKG, ExtensionJarInstallationCoordinator.Intent.INSTALL)
                assertEquals(2, transport.downloadCount)
                assertCommitted(3, "$TARGET_PKG.apk", listOf(SOURCE_ID))
                assertTrue(transport.paths.all { !Files.exists(it) })
                assertTrue(ExtensionActivationJournal(extensionsRoot).loadAll().isEmpty())
            }
        }

    @Test
    fun `continuous catalogue churn stops with a stale conflict without publishing or retaining staging`() =
        runBlocking {
            withFixtureDatabase {
                seedStore(2)
                var version = 2L
                val transport =
                    FakeTransport {
                        version++
                        ExtensionsList.reconcileExtensionDatabase(listOf(extension(version)), listOf(store()))
                    }
                assertThrows(StaleExtensionStoreSnapshotException::class.java) {
                    runBlocking {
                        coordinator(
                            transport,
                            committedEffects(mutableListOf()),
                        ).execute(TARGET_PKG, ExtensionJarInstallationCoordinator.Intent.INSTALL)
                    }
                }
                assertEquals(3, transport.downloadCount)
                assertFalse(extensionInstalled())
                assertTrue(transport.paths.all { !Files.exists(it) })
                assertTrue(ExtensionActivationJournal(extensionsRoot).loadAll().isEmpty())
            }
        }

    @Test
    fun `fresh intent composes authenticated pipeline and observes commit before runtime effects`() =
        runBlocking {
            withFixtureDatabase {
                seedStore(versionCode = 2)
                val transport = FakeTransport()
                val effectEvents = mutableListOf<String>()
                val coordinator = coordinator(transport, committedEffects(effectEvents))

                val outcome = coordinator.execute(TARGET_PKG, ExtensionJarInstallationCoordinator.Intent.INSTALL)

                val active = extensionsRoot.resolve("$TARGET_PKG.jar")
                assertEquals("candidate-2", Files.readString(active))
                assertEquals(listOf(SOURCE_ID), outcome.metadata.installedSourceIDs)
                assertTrue(outcome.postCommit.isComplete)
                assertEquals(
                    listOf("close:$TARGET_PKG.jar", "source:$SOURCE_ID", "update:$TARGET_PKG"),
                    effectEvents,
                )
                assertCommitted(versionCode = 2, apkName = "$TARGET_PKG.apk", sourceIDs = listOf(SOURCE_ID))
                assertTrue(ExtensionActivationJournal(extensionsRoot).loadAll().isEmpty())
                assertTrue(markers().isEmpty())
                assertFalse(Files.exists(transport.lastPath))
            }
        }

    @Test
    fun `upgrade retires legacy artifact and invalidates old plus new runtime identities`() =
        runBlocking {
            withFixtureDatabase {
                seedStore(versionCode = 2)
                transaction {
                    val extensionID = extensionID()
                    ExtensionTable.update({ ExtensionTable.id eq extensionID }) {
                        it[apkName] = "legacy.apk"
                        it[isInstalled] = true
                        it[versionCode] = 1
                        it[versionName] = "1.4.1"
                    }
                    SourceTable.insert {
                        it[id] = OLD_SOURCE_ID
                        it[name] = "Old source"
                        it[lang] = "en"
                        it[extension] = extensionID
                    }
                }
                val previous = Files.writeString(extensionsRoot.resolve("legacy.jar"), "old")
                val events = mutableListOf<String>()

                val outcome =
                    coordinator(FakeTransport(), committedEffects(events)).execute(
                        TARGET_PKG,
                        ExtensionJarInstallationCoordinator.Intent.UPGRADE,
                    )

                assertFalse(Files.exists(previous))
                assertEquals("candidate-2", Files.readString(extensionsRoot.resolve("$TARGET_PKG.jar")))
                assertEquals(listOf(OLD_SOURCE_ID), outcome.metadata.previousSourceIDs)
                assertEquals(listOf(SOURCE_ID), outcome.metadata.installedSourceIDs)
                assertEquals(
                    listOf(
                        "close:legacy.jar",
                        "close:$TARGET_PKG.jar",
                        "source:$OLD_SOURCE_ID",
                        "source:$SOURCE_ID",
                        "update:$TARGET_PKG",
                    ),
                    events,
                )
                assertCommitted(versionCode = 2, apkName = "$TARGET_PKG.apk", sourceIDs = listOf(SOURCE_ID))
            }
        }

    @Test
    fun `post-commit failure is returned without misreporting committed activation as rollback`() =
        runBlocking {
            withFixtureDatabase {
                seedStore(versionCode = 2)
                val effects =
                    ExtensionJarPostCommitEffects(
                        closeClassLoader = { throw IllegalStateException("loader busy") },
                        unregisterSource = {},
                        clearAvailableUpdate = {},
                    )

                val outcome =
                    coordinator(FakeTransport(), effects).execute(
                        TARGET_PKG,
                        ExtensionJarInstallationCoordinator.Intent.INSTALL,
                    )

                assertFalse(outcome.postCommit.isComplete)
                assertEquals(1, outcome.postCommit.failures.size)
                assertEquals(
                    ExtensionJarPostCommitEffects.Step.CLOSE_CLASS_LOADER,
                    outcome.postCommit.failures
                        .single()
                        .step,
                )
                assertCommitted(versionCode = 2, apkName = "$TARGET_PKG.apk", sourceIDs = listOf(SOURCE_ID))
                assertEquals("candidate-2", Files.readString(extensionsRoot.resolve("$TARGET_PKG.jar")))
                assertTrue(ExtensionActivationJournal(extensionsRoot).loadAll().isEmpty())
                assertTrue(markers().isEmpty())
            }
        }

    @Test
    fun `invalid operation intent closes preflight staging before durable handoff`() =
        runBlocking {
            withFixtureDatabase {
                seedStore(versionCode = 2)
                val transport = FakeTransport()

                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        coordinator(transport, committedEffects(mutableListOf())).execute(
                            TARGET_PKG,
                            ExtensionJarInstallationCoordinator.Intent.UPGRADE,
                        )
                    }
                }

                assertFalse(Files.exists(transport.lastPath))
                assertFalse(Files.exists(extensionsRoot.resolve("$TARGET_PKG.jar")))
                assertTrue(ExtensionActivationJournal(extensionsRoot).loadAll().isEmpty())
                assertTrue(markers().isEmpty())
                assertFalse(extensionInstalled())
            }
        }

    private fun coordinator(
        transport: FakeTransport,
        effects: ExtensionJarPostCommitEffects,
    ) = ExtensionJarInstallationCoordinator(
        extensionsRoot = extensionsRoot,
        databaseIdentity = DATABASE_IDENTITY,
        preflightCoordinator =
            ExtensionJarPreflightCoordinator(
                transportSelector = { transport },
                pipelineFactory = {
                    ExtensionJarPreflightPipeline(
                        transport = it,
                        artifactInspector =
                            ExtensionJarPreflightPipeline.ArtifactInspector { _, expected, signingKey ->
                                artifactMetadata(expected, signingKey)
                            },
                        shadowValidator =
                            ExtensionJarPreflightPipeline.ShadowValidator { _, _, expected, _ ->
                                ExtensionJarShadowValidator.Result(
                                    sources =
                                        expected.sources.map { source ->
                                            ExtensionJarShadowValidator.SourceMetadata(source.id, source.name, source.lang)
                                        },
                                    extensionLanguage = expected.lang,
                                )
                            },
                    )
                },
            ),
        postCommitEffects = effects,
    )

    private fun committedEffects(events: MutableList<String>) =
        ExtensionJarPostCommitEffects(
            closeClassLoader = {
                assertTrue(extensionInstalled())
                assertTrue(Files.isRegularFile(extensionsRoot.resolve("$TARGET_PKG.jar")))
                assertTrue(Files.isRegularFile(it), "classloader must close before journal retires its artifact")
                events += "close:${it.fileName}"
            },
            unregisterSource = {
                assertTrue(extensionInstalled())
                events += "source:$it"
            },
            clearAvailableUpdate = {
                assertTrue(extensionInstalled())
                events += "update:$it"
            },
        )

    private inner class FakeTransport(
        private val duringDownload: suspend () -> Unit = {},
    ) : ExtensionArtifactTransport {
        var downloadCount = 0
        val paths = mutableListOf<Path>()
        lateinit var lastPath: Path
            private set

        override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact {
            val version =
                request.url
                    .substringAfterLast('/')
                    .substringBefore('.')
                    .toLong()
            lastPath = Files.writeString(request.stagingDirectory.resolve("candidate-${UUID.randomUUID()}.jar"), "candidate-$version")
            paths.add(lastPath)
            downloadCount++
            duringDownload()
            return StagedArtifact(
                path = lastPath,
                size = Files.size(lastPath),
                sha256 = StagedArtifact.sha256(lastPath, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES),
                finalUrl = request.url,
                kind = ArtifactKind.JAR,
            )
        }
    }

    private suspend fun seedStore(versionCode: Long) {
        val store = store()
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
        ExtensionsList.reconcileExtensionDatabase(listOf(extension(versionCode)), listOf(store))
    }

    private fun artifactMetadata(
        expected: ExtensionInfo,
        signingKey: String,
    ): ExtensionJarArtifactInspector.Metadata {
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
                    signerFingerprint = signingKey,
                    verifiedEntries = 1,
                    uncompressedBytes = 3,
                    verifiedEntryNames = setOf(className.replace('.', '/') + ".class"),
                ),
        )
    }

    private fun store() =
        ExtensionStore(
            indexUrl = STORE_URL,
            name = "Fixture store",
            badgeLabel = "fixture",
            signingKey = SIGNING_KEY,
            contact = ExtensionStore.Contact("https://contact.invalid", null),
            isLegacy = false,
            extensionListUrl = "$STORE_URL/extensions.pb",
        )

    private fun extension(versionCode: Long) =
        ExtensionInfo(
            storeIndexUrl = STORE_URL,
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
            sources =
                listOf(
                    ExtensionSource(
                        id = SOURCE_ID,
                        name = "Fixture source",
                        lang = "en",
                        homeUrl = "https://source.invalid",
                        message = null,
                        contentWarning = ContentWarning.SAFE,
                    ),
                ),
        )

    private fun extensionID() =
        ExtensionTable
            .selectAll()
            .where { ExtensionTable.pkgName eq TARGET_PKG }
            .single()[ExtensionTable.id]

    private fun extensionInstalled(): Boolean =
        transaction {
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.pkgName eq TARGET_PKG }
                .single()[ExtensionTable.isInstalled]
        }

    private fun assertCommitted(
        versionCode: Long,
        apkName: String,
        sourceIDs: List<Long>,
    ) {
        transaction {
            val extension =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName eq TARGET_PKG }
                    .single()
            assertTrue(extension[ExtensionTable.isInstalled])
            assertEquals(versionCode, extension[ExtensionTable.versionCode])
            assertEquals(apkName, extension[ExtensionTable.apkName])
            assertEquals(
                sourceIDs,
                SourceTable
                    .selectAll()
                    .where { SourceTable.extension eq extension[ExtensionTable.id] }
                    .map { it[SourceTable.id].value }
                    .sorted(),
            )
        }
    }

    private fun markers(): List<String> =
        transaction {
            ExtensionActivationMarkerTable.selectAll().map { it[ExtensionActivationMarkerTable.markerKey] }
        }

    private suspend fun withFixtureDatabase(block: suspend () -> Unit) {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase =
            Database.connect(
                "jdbc:h2:mem:jar_install_coordinator_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
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
        const val STORE_URL = "https://store.invalid/index.pb"
        const val SIGNING_KEY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATABASE_IDENTITY = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val TARGET_PKG = "app.komu.fixture.target"
        const val SOURCE_ID = 201L
        const val OLD_SOURCE_ID = 101L
    }
}
