package suwayomi.tachidesk.manga.impl.extension

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class ExtensionJarMetadataWriterTest : ApplicationTest() {
    @Test
    fun `fresh authenticated install persists exact extension and per-source metadata`() =
        runBlocking {
            withFixtureDatabase {
                val metadata =
                    metadata(
                        sources =
                            listOf(
                                source(201, "English", "en", ContentWarning.SAFE),
                                source(202, "Japanese", "ja", ContentWarning.NSFW),
                            ),
                        extensionLanguage = "all",
                        contentWarning = ContentWarning.MIXED,
                    )

                val result =
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata,
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                    }

                assertTrue(result.extensionID > 0)
                assertEquals(emptyList<Long>(), result.previousSourceIDs)
                assertEquals(listOf(201L, 202L), result.installedSourceIDs)
                transaction {
                    val extension = extensionRow()
                    assertEquals("fixture.apk", extension[ExtensionTable.apkName])
                    assertEquals(STORE_URL, extension[ExtensionTable.repo])
                    assertEquals(STORE_URL, extension[ExtensionTable.storeIndexUrl])
                    assertEquals(JAR_URL, extension[ExtensionTable.jarUrl])
                    assertEquals("https://source.invalid/201", extension[ExtensionTable.baseUrl])
                    assertEquals("en,ja", extension[ExtensionTable.sourceLangs])
                    assertTrue(extension[ExtensionTable.isInstalled])
                    assertFalse(extension[ExtensionTable.isCustomPinned])
                    val sources = sourceRows(result.extensionID)
                    assertEquals(listOf(201L, 202L), sources.map { it[SourceTable.id].value })
                    assertEquals(ContentWarning.SAFE.ordinal, sources[0][SourceTable.contentWarning])
                    assertEquals(ContentWarning.NSFW.ordinal, sources[1][SourceTable.contentWarning])
                    assertFalse(sources[0][SourceTable.isNsfw])
                    assertTrue(sources[1][SourceTable.isNsfw])
                }
            }
        }

    @Test
    fun `upgrade replaces owned sources and returns post-commit invalidation evidence`() =
        runBlocking {
            withFixtureDatabase {
                val extensionID = insertExtension(versionCode = 1, installed = true)
                insertSource(extensionID, sourceID = 101)

                val result =
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata(versionCode = 2, sources = listOf(source(201))),
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                    }

                assertEquals(extensionID, result.extensionID)
                assertEquals(listOf(101L), result.previousSourceIDs)
                assertEquals(listOf(201L), result.installedSourceIDs)
                transaction {
                    assertEquals(2L, extensionRow()[ExtensionTable.versionCode])
                    assertEquals(listOf(201L), sourceRows(extensionID).map { it[SourceTable.id].value })
                }
            }
        }

    @Test
    fun `automatic policy rejects downgrade without mutating installed metadata`() =
        runBlocking {
            withFixtureDatabase {
                val extensionID = insertExtension(versionCode = 3, installed = true)
                insertSource(extensionID, sourceID = 301)

                assertThrows(IllegalStateException::class.java) {
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata(versionCode = 2),
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                    }
                }

                transaction {
                    assertEquals(3L, extensionRow()[ExtensionTable.versionCode])
                    assertEquals(listOf(301L), sourceRows(extensionID).map { it[SourceTable.id].value })
                }
            }
        }

    @Test
    fun `same version requires explicit reinstall policy`() =
        runBlocking {
            withFixtureDatabase {
                val extensionID = insertExtension(versionCode = 2, installed = true)
                insertSource(extensionID, sourceID = 101)
                val metadata = metadata(versionCode = 2)

                assertThrows(IllegalStateException::class.java) {
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata,
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                    }
                }
                val result =
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata,
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.REINSTALL_SAME_VERSION,
                        )
                    }

                assertEquals(listOf(101L), result.previousSourceIDs)
                assertEquals(listOf(SOURCE_ID), result.installedSourceIDs)
            }
        }

    @Test
    fun `custom-pinned artifact requires explicit store replacement policy`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(versionCode = 10, installed = true, customPinned = true, storeIndexUrl = null)
                val metadata = metadata(versionCode = 2)

                assertThrows(IllegalStateException::class.java) {
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata,
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                    }
                }
                transaction {
                    ExtensionJarMetadataWriter.write(
                        this,
                        metadata,
                        ExtensionJarMetadataWriter.ExistingArtifactPolicy.REPLACE_CUSTOM_PINNED,
                    )
                }

                transaction {
                    val row = extensionRow()
                    assertEquals(2L, row[ExtensionTable.versionCode])
                    assertEquals(STORE_URL, row[ExtensionTable.storeIndexUrl])
                    assertFalse(row[ExtensionTable.isCustomPinned])
                }
            }
        }

    @Test
    fun `duplicate package rows fail closed before any metadata mutation`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(versionCode = 1, installed = false)
                insertExtension(versionCode = 1, installed = false)

                assertThrows(IllegalStateException::class.java) {
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata(versionCode = 2),
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                    }
                }

                transaction {
                    assertEquals(2, ExtensionTable.selectAll().where { ExtensionTable.pkgName eq TARGET_PKG }.count())
                    assertTrue(SourceTable.selectAll().toList().isEmpty())
                }
            }
        }

    @Test
    fun `foreign source ownership fails before target sources are deleted`() =
        runBlocking {
            withFixtureDatabase {
                val targetID = insertExtension(versionCode = 1, installed = false)
                insertSource(targetID, sourceID = 101)
                val foreignID = insertExtension(OTHER_PKG, versionCode = 1, installed = true)
                insertSource(foreignID, sourceID = SOURCE_ID)

                assertThrows(IllegalStateException::class.java) {
                    transaction {
                        ExtensionJarMetadataWriter.write(
                            this,
                            metadata(versionCode = 2),
                            ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                        )
                    }
                }

                transaction {
                    assertEquals(listOf(101L), sourceRows(targetID).map { it[SourceTable.id].value })
                    assertEquals(listOf(SOURCE_ID), sourceRows(foreignID).map { it[SourceTable.id].value })
                    assertFalse(extensionRow()[ExtensionTable.isInstalled])
                }
            }
        }

    @Test
    fun `writer refuses an expired transaction object`() =
        runBlocking {
            withFixtureDatabase {
                lateinit var expired: JdbcTransaction
                transaction { expired = this }

                assertThrows(IllegalStateException::class.java) {
                    ExtensionJarMetadataWriter.write(
                        expired,
                        metadata(),
                        ExtensionJarMetadataWriter.ExistingArtifactPolicy.INSTALL_OR_UPGRADE,
                    )
                }
                transaction { assertTrue(ExtensionTable.selectAll().toList().isEmpty()) }
            }
        }

    private fun metadata(
        versionCode: Long = 2,
        sources: List<ExtensionSource> = listOf(source(SOURCE_ID)),
        extensionLanguage: String = "en",
        contentWarning: ContentWarning = ContentWarning.SAFE,
    ): ExtensionJarActivationOrchestrator.ActivationMetadata {
        val expected =
            ExtensionInfo(
                storeIndexUrl = STORE_URL,
                name = "Fixture extension",
                pkgName = TARGET_PKG,
                apkUrl = "https://cdn.invalid/$versionCode.apk",
                jarUrl = JAR_URL,
                iconUrl = "https://cdn.invalid/icon.png",
                extensionLib = "1.4",
                versionCode = versionCode,
                versionName = "1.4.$versionCode",
                lang = extensionLanguage,
                contentWarning = contentWarning,
                sources = sources,
            )
        val className = "$TARGET_PKG.FixtureSource"
        return ExtensionJarActivationOrchestrator.ActivationMetadata(
            expected = expected,
            artifact =
                ExtensionJarArtifactInspector.Metadata(
                    packageName = TARGET_PKG,
                    versionCode = versionCode,
                    versionName = expected.versionName,
                    extensionLib = expected.extensionLib,
                    name = expected.name,
                    classFQName = className,
                    contentWarning = contentWarning,
                    signature =
                        ExtensionJarVerifier.Result(
                            signerFingerprint = SIGNING_KEY,
                            verifiedEntries = 1,
                            uncompressedBytes = 3,
                            verifiedEntryNames = setOf(className.replace('.', '/') + ".class"),
                        ),
                ),
            sources = sources.map { ExtensionJarShadowValidator.SourceMetadata(it.id, it.name, it.lang) },
            extensionLanguage = extensionLanguage,
            finalUrl = JAR_URL,
            activeJarName = "fixture.jar",
        )
    }

    private fun insertExtension(
        packageName: String = TARGET_PKG,
        versionCode: Long,
        installed: Boolean,
        customPinned: Boolean = false,
        storeIndexUrl: String? = STORE_URL,
    ): Int =
        transaction {
            ExtensionTable
                .insertAndGetId {
                    it[name] = "Existing extension"
                    it[pkgName] = packageName
                    it[extensionLib] = "1.4"
                    it[versionName] = "1.4.$versionCode"
                    it[ExtensionTable.versionCode] = versionCode
                    it[lang] = "en"
                    it[contentWarning] = ContentWarning.SAFE.ordinal
                    it[isInstalled] = installed
                    it[isCustomPinned] = customPinned
                    it[ExtensionTable.storeIndexUrl] = storeIndexUrl
                    it[repo] = storeIndexUrl
                }.value
        }

    private fun insertSource(
        extensionID: Int,
        sourceID: Long,
    ) {
        transaction {
            SourceTable.insert {
                it[id] = sourceID
                it[name] = "Existing source $sourceID"
                it[lang] = "en"
                it[extension] = extensionID
            }
        }
    }

    private fun extensionRow() =
        ExtensionTable
            .selectAll()
            .where { ExtensionTable.pkgName eq TARGET_PKG }
            .single()

    private fun sourceRows(extensionID: Int) =
        SourceTable
            .selectAll()
            .where { SourceTable.extension eq extensionID }
            .sortedBy { it[SourceTable.id].value }

    private suspend fun withFixtureDatabase(block: suspend () -> Unit) {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase =
            Database.connect(
                "jdbc:h2:mem:jar_metadata_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
                databaseConfig =
                    DatabaseConfig {
                        useNestedTransactions = true
                        @OptIn(ExperimentalKeywordApi::class)
                        preserveKeywordCasing = false
                    },
            )
        try {
            runMigrations(
                loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java),
                fixtureDatabase,
            )
            TransactionManager.defaultDatabase = fixtureDatabase
            block()
        } finally {
            TransactionManager.closeAndUnregister(fixtureDatabase)
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }

    private fun source(
        id: Long,
        name: String = "Fixture source $id",
        lang: String = "en",
        contentWarning: ContentWarning = ContentWarning.SAFE,
    ) = ExtensionSource(
        id = id,
        name = name,
        lang = lang,
        homeUrl = "https://source.invalid/$id",
        message = null,
        contentWarning = contentWarning,
    )

    private companion object {
        const val STORE_URL = "https://store.invalid/index.pb"
        const val JAR_URL = "https://cdn.invalid/fixture.jar"
        const val SIGNING_KEY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TARGET_PKG = "app.komu.fixture.target"
        const val OTHER_PKG = "app.komu.fixture.other"
        const val SOURCE_ID = 201L
    }
}
