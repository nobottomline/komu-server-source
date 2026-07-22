package suwayomi.tachidesk.manga.impl.extension

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ExtensionJarInstallationStateRepositoryTest : ApplicationTest() {
    @TempDir
    lateinit var extensionsRoot: Path

    @Test
    fun `fresh install reserves canonical package artifact without previous state`() =
        runBlocking {
            withFixtureDatabase {
                val plan = plan(ExtensionJarInstallationCoordinator.Intent.INSTALL)

                assertEquals(extensionsRoot.resolve("$TARGET_PKG.jar"), plan.activeArtifact)
                assertNull(plan.previousArtifact)
            }
        }

    @Test
    fun `upgrade requires newer same-store installed state and preserves legacy previous path`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(versionCode = 1, installed = true, apkName = "legacy-name.apk")
                val previous = Files.writeString(extensionsRoot.resolve("legacy-name.jar"), "old")

                val plan = plan(ExtensionJarInstallationCoordinator.Intent.UPGRADE, expectedVersion = 2)

                assertEquals(extensionsRoot.resolve("$TARGET_PKG.jar"), plan.activeArtifact)
                assertEquals(previous, plan.previousArtifact)
            }
        }

    @Test
    fun `same-version reinstall accepts a target-owned canonical artifact`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(versionCode = 2, installed = true, apkName = "$TARGET_PKG.apk")
                val active = Files.writeString(extensionsRoot.resolve("$TARGET_PKG.jar"), "old")

                val plan = plan(ExtensionJarInstallationCoordinator.Intent.REINSTALL, expectedVersion = 2)

                assertEquals(active, plan.activeArtifact)
                assertEquals(active, plan.previousArtifact)
            }
        }

    @Test
    fun `intent matrix rejects accidental install upgrade reinstall and custom replacement crossover`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(versionCode = 2, installed = true, apkName = "installed.apk")
                Files.writeString(extensionsRoot.resolve("installed.jar"), "old")

                assertThrows(IllegalStateException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.INSTALL, expectedVersion = 3)
                }
                assertThrows(IllegalStateException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.UPGRADE, expectedVersion = 2)
                }
                assertThrows(IllegalStateException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.REINSTALL, expectedVersion = 3)
                }
                assertThrows(IllegalStateException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.REPLACE_CUSTOM, expectedVersion = 3)
                }
            }
        }

    @Test
    fun `explicit custom replacement accepts independent version lineage`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(
                    versionCode = 99,
                    installed = true,
                    customPinned = true,
                    storeIndexUrl = null,
                    apkName = "custom.apk",
                )
                val previous = Files.writeString(extensionsRoot.resolve("custom.jar"), "custom")

                val plan = plan(ExtensionJarInstallationCoordinator.Intent.REPLACE_CUSTOM, expectedVersion = 2)

                assertEquals(previous, plan.previousArtifact)
            }
        }

    @Test
    fun `foreign database claim blocks canonical package artifact even when file is absent`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(
                    packageName = OTHER_PKG,
                    versionCode = 1,
                    installed = true,
                    apkName = "$TARGET_PKG.apk",
                )

                assertThrows(IllegalStateException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.INSTALL)
                }
            }
        }

    @Test
    fun `unknown canonical file is never overwritten by a fresh install`() =
        runBlocking {
            withFixtureDatabase {
                Files.writeString(extensionsRoot.resolve("$TARGET_PKG.jar"), "unknown")

                assertThrows(IllegalStateException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.INSTALL)
                }
            }
        }

    @Test
    fun `installed row with unsafe or missing previous artifact fails closed`() =
        runBlocking {
            withFixtureDatabase {
                insertExtension(versionCode = 1, installed = true, apkName = "../escape.apk")

                assertThrows(IllegalArgumentException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.UPGRADE, expectedVersion = 2)
                }
            }
            withFixtureDatabase {
                insertExtension(versionCode = 1, installed = true, apkName = "missing.apk")

                assertThrows(IllegalArgumentException::class.java) {
                    plan(ExtensionJarInstallationCoordinator.Intent.UPGRADE, expectedVersion = 2)
                }
            }
        }

    private fun plan(
        intent: ExtensionJarInstallationCoordinator.Intent,
        expectedVersion: Long = 2,
    ) = ExtensionJarInstallationStateRepository.plan(
        packageName = TARGET_PKG,
        expected = extensionInfo(expectedVersion),
        intent = intent,
        extensionsRoot = extensionsRoot,
    )

    private fun insertExtension(
        packageName: String = TARGET_PKG,
        versionCode: Long,
        installed: Boolean,
        customPinned: Boolean = false,
        storeIndexUrl: String? = STORE_URL,
        apkName: String?,
    ) {
        transaction {
            ExtensionTable.insert {
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
                it[ExtensionTable.apkName] = apkName
            }
        }
    }

    private fun extensionInfo(versionCode: Long) =
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
                        id = 201,
                        name = "Fixture source",
                        lang = "en",
                        homeUrl = "https://source.invalid",
                        message = null,
                        contentWarning = ContentWarning.SAFE,
                    ),
                ),
        )

    private suspend fun withFixtureDatabase(block: suspend () -> Unit) {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase =
            Database.connect(
                "jdbc:h2:mem:jar_install_state_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
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

    private companion object {
        const val STORE_URL = "https://store.invalid/index.pb"
        const val TARGET_PKG = "eu.kanade.tachiyomi.extension.all.fixture"
        const val OTHER_PKG = "eu.kanade.tachiyomi.extension.all.other"
    }
}
