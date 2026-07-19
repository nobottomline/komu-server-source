package suwayomi.tachidesk.manga.impl.extension

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class ExtensionCompatibilityReconciliationTest : ApplicationTest() {
    @Test
    fun `store reconciliation dual-writes compatibility fields without taking ownership from custom installs`() =
        runBlocking {
            withFixtureDatabase { database ->
                insertFixtureRows(database)

                val pinnedCandidate = storeExtension(PINNED_PKG, 30, ContentWarning.MIXED)
                val regularCandidate = storeExtension(REGULAR_PKG, 30, ContentWarning.MIXED)
                val uninstalledCandidate = storeExtension(UNINSTALLED_PKG, 3_000_000_000, ContentWarning.NSFW)

                ExtensionsList.reconcileExtensionDatabase(
                    listOf(pinnedCandidate, regularCandidate, uninstalledCandidate),
                )

                val rows = readRows(database)
                val pinned = rows.getValue(PINNED_PKG)
                assertEquals(22, pinned.versionCode)
                assertEquals("custom-22.apk", pinned.apkName)
                assertEquals(ContentWarning.NSFW.ordinal, pinned.contentWarning)
                assertTrue(pinned.isNsfw)
                assertTrue(pinned.isCustomPinned)
                assertFalse(pinned.hasUpdate)
                assertFalse(pinned.isObsolete)
                assertEquals(STORE_URL, pinned.repo)
                assertEquals(STORE_URL, pinned.storeIndexUrl)
                assertEquals("https://cdn.invalid/$PINNED_PKG.apk", pinned.apkUrl)
                assertEquals("https://cdn.invalid/$PINNED_PKG.jar", pinned.jarUrl)
                assertEquals("https://custom.invalid", pinned.baseUrl)
                assertEquals("ru", pinned.sourceLangs)
                assertNull(ExtensionsList.updateMap[PINNED_PKG])

                val regular = rows.getValue(REGULAR_PKG)
                assertEquals(20, regular.versionCode)
                assertTrue(regular.hasUpdate)
                assertFalse(regular.isObsolete)
                assertEquals(regularCandidate, ExtensionsList.updateMap[REGULAR_PKG])

                val uninstalled = rows.getValue(UNINSTALLED_PKG)
                assertEquals(3_000_000_000, uninstalled.versionCode)
                assertEquals(ContentWarning.NSFW.ordinal, uninstalled.contentWarning)
                assertTrue(uninstalled.isNsfw)
                assertFalse(uninstalled.isCustomPinned)
                assertEquals(STORE_URL, uninstalled.repo)
                assertEquals(STORE_URL, uninstalled.storeIndexUrl)

                val restProjection =
                    ExtensionsList.extensionTableAsDataClass().first { it.pkgName == UNINSTALLED_PKG }
                assertEquals(3_000_000_000, restProjection.versionCode)
                assertEquals(STORE_URL, restProjection.repo)
                assertEquals(STORE_URL, restProjection.storeIndexUrl)
                assertEquals("https://source.invalid/$UNINSTALLED_PKG", restProjection.baseUrl)
                assertEquals("en,ja", restProjection.sourceLangs)
                assertEquals(ContentWarning.NSFW, restProjection.contentWarning)
            }
        }

    @Test
    fun `missing store entries never obsolete a custom install but do obsolete a regular install`() =
        runBlocking {
            withFixtureDatabase { database ->
                insertFixtureRows(database)

                ExtensionsList.reconcileExtensionDatabase(emptyList())

                val rows = readRows(database)
                val pinned = rows.getValue(PINNED_PKG)
                assertTrue(pinned.isCustomPinned)
                assertFalse(pinned.hasUpdate)
                assertFalse(pinned.isObsolete)

                val regular = rows.getValue(REGULAR_PKG)
                assertFalse(regular.hasUpdate)
                assertTrue(regular.isObsolete)
            }
        }

    private suspend fun withFixtureDatabase(block: suspend (Database) -> Unit) {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase = createFixtureDatabase()
        ExtensionsList.updateMap.clear()

        try {
            runMigrations(
                loadMigrationsFrom(
                    "suwayomi.tachidesk.server.database.migration",
                    ServerConfig::class.java,
                ),
                fixtureDatabase,
            )
            TransactionManager.defaultDatabase = fixtureDatabase
            block(fixtureDatabase)
        } finally {
            ExtensionsList.updateMap.clear()
            TransactionManager.closeAndUnregister(fixtureDatabase)
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }

    private fun createFixtureDatabase(): Database =
        Database.connect(
            "jdbc:h2:mem:extension_compat_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig =
                DatabaseConfig {
                    useNestedTransactions = true
                    @OptIn(ExperimentalKeywordApi::class)
                    preserveKeywordCasing = false
                },
        )

    private fun insertFixtureRows(database: Database) {
        transaction(database) {
            insertFixtureRow(
                pkgName = PINNED_PKG,
                apkName = "custom-22.apk",
                versionCode = 22,
                installed = true,
                customPinned = true,
                hasUpdate = true,
                obsolete = true,
                contentWarning = ContentWarning.NSFW,
            )
            insertFixtureRow(
                pkgName = REGULAR_PKG,
                apkName = "regular-20.apk",
                versionCode = 20,
                installed = true,
                customPinned = false,
            )
            insertFixtureRow(
                pkgName = UNINSTALLED_PKG,
                apkName = null,
                versionCode = 10,
                installed = false,
                customPinned = true,
            )
        }
    }

    private fun JdbcTransaction.insertFixtureRow(
        pkgName: String,
        apkName: String?,
        versionCode: Long,
        installed: Boolean,
        customPinned: Boolean,
        hasUpdate: Boolean = false,
        obsolete: Boolean = false,
        contentWarning: ContentWarning = ContentWarning.SAFE,
    ) {
        val quotedApkName = apkName?.let { "'$it'" } ?: "NULL"
        exec(
            """
            INSERT INTO EXTENSION (
                APK_NAME, REPO, STORE_INDEX_URL, NAME, PKG_NAME, APK_URL, JAR_URL,
                EXTENSION_LIB, VERSION_NAME, VERSION_CODE, LANG, CONTENT_WARNING,
                IS_NSFW, IS_INSTALLED, HAS_UPDATE, IS_OBSOLETE, IS_CUSTOM_PINNED,
                BASE_URL, SOURCE_LANGS
            ) VALUES (
                $quotedApkName, '$STORE_URL', '$STORE_URL', 'Fixture', '$pkgName',
                'https://old.invalid/$pkgName.apk', NULL, '1.4', '1.4.$versionCode',
                $versionCode, 'en', ${contentWarning.ordinal},
                ${contentWarning >= ContentWarning.MIXED}, $installed, $hasUpdate, $obsolete, $customPinned,
                'https://custom.invalid', 'ru'
            )
            """.trimIndent(),
        )
    }

    private fun readRows(database: Database): Map<String, RowSnapshot> =
        transaction(database) {
            exec(
                """
                SELECT PKG_NAME, APK_NAME, REPO, STORE_INDEX_URL, APK_URL, JAR_URL,
                       VERSION_CODE, CONTENT_WARNING, IS_NSFW, IS_CUSTOM_PINNED,
                       HAS_UPDATE, IS_OBSOLETE, BASE_URL, SOURCE_LANGS
                FROM EXTENSION
                """.trimIndent(),
            ) { result ->
                buildMap {
                    while (result.next()) {
                        val pkgName = result.getString("PKG_NAME")
                        put(
                            pkgName,
                            RowSnapshot(
                                apkName = result.getString("APK_NAME"),
                                repo = result.getString("REPO"),
                                storeIndexUrl = result.getString("STORE_INDEX_URL"),
                                apkUrl = result.getString("APK_URL"),
                                jarUrl = result.getString("JAR_URL"),
                                versionCode = result.getLong("VERSION_CODE"),
                                contentWarning = result.getInt("CONTENT_WARNING"),
                                isNsfw = result.getBoolean("IS_NSFW"),
                                isCustomPinned = result.getBoolean("IS_CUSTOM_PINNED"),
                                hasUpdate = result.getBoolean("HAS_UPDATE"),
                                isObsolete = result.getBoolean("IS_OBSOLETE"),
                                baseUrl = result.getString("BASE_URL"),
                                sourceLangs = result.getString("SOURCE_LANGS"),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }

    private fun storeExtension(
        pkgName: String,
        versionCode: Long,
        contentWarning: ContentWarning,
    ) = ExtensionInfo(
        storeIndexUrl = STORE_URL,
        name = "Store $pkgName",
        pkgName = pkgName,
        apkUrl = "https://cdn.invalid/$pkgName.apk",
        jarUrl = "https://cdn.invalid/$pkgName.jar",
        iconUrl = "https://cdn.invalid/$pkgName.png",
        extensionLib = "1.4",
        versionCode = versionCode,
        versionName = "1.4.$versionCode",
        lang = "all",
        contentWarning = contentWarning,
        sources =
            listOf(
                ExtensionSource(1, "English", "en", "https://source.invalid/$pkgName", null, contentWarning),
                ExtensionSource(2, "Japanese", "ja", "https://ja.invalid/$pkgName", null, contentWarning),
            ),
    )

    private data class RowSnapshot(
        val apkName: String?,
        val repo: String?,
        val storeIndexUrl: String?,
        val apkUrl: String?,
        val jarUrl: String?,
        val versionCode: Long,
        val contentWarning: Int,
        val isNsfw: Boolean,
        val isCustomPinned: Boolean,
        val hasUpdate: Boolean,
        val isObsolete: Boolean,
        val baseUrl: String?,
        val sourceLangs: String?,
    )

    private companion object {
        const val STORE_URL = "https://store.invalid/index.min.json"
        const val PINNED_PKG = "app.komu.pinned"
        const val REGULAR_PKG = "app.komu.regular"
        const val UNINSTALLED_PKG = "app.komu.uninstalled"
    }
}
