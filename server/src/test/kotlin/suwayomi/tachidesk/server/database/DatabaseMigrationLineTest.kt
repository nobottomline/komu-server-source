package suwayomi.tachidesk.server.database

import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

/** Protects the published Komu migration history from upstream number reuse. */
class DatabaseMigrationLineTest : ApplicationTest() {
    @Test
    fun `fresh database records the collision-safe ledger through version 64`() {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase = createFixtureDatabase("komu_fresh")

        try {
            runMigrations(loadMigrationLine(), fixtureDatabase)

            assertEquals(expectedLedger, readLedger(fixtureDatabase))
            val columns = readColumns(fixtureDatabase)
            assertTrue(expectedCompatibilityColumns.all(columns::contains))
            assertTrue(expectedTargetColumns.all(columns::contains))
            assertTrue(expectedSyncYomiColumns.all(columns::contains))
            assertEquals(expectedSyncYomiTriggers, readTriggers(fixtureDatabase))
            assertModernWritesUseLegacyDefaults(fixtureDatabase)
        } finally {
            TransactionManager.closeAndUnregister(fixtureDatabase)
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }

    @Test
    fun `released Komu version 57 data survives all reconciled upstream migrations`() {
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val fixtureDatabase = createFixtureDatabase("komu_v57")

        try {
            val migrations = loadMigrationLine()

            runMigrations(migrations.filter { it.version <= 57 }, fixtureDatabase)
            insertReleasedKomuData(fixtureDatabase)
            runMigrations(migrations.filter { it.version <= 64 }, fixtureDatabase)

            assertEquals(expectedLedger, readLedger(fixtureDatabase))
            assertEquals(
                ExtensionSnapshot(
                    baseUrl = "https://fixture.invalid",
                    sourceLangs = "en,ja",
                    isCustomPinned = true,
                    repo = "https://repo.invalid/index.min.json",
                    storeIndexUrl = "https://repo.invalid/index.min.json",
                    isNsfw = true,
                    contentWarning = ContentWarning.MIXED.ordinal,
                    extensionLib = "1.4",
                ),
                readExtensionSnapshot(fixtureDatabase),
            )
            assertEquals("fixture-key" to "fixture-value", readMetadataSnapshot(fixtureDatabase))

            val columns = readColumns(fixtureDatabase)
            assertTrue(expectedCompatibilityColumns.all(columns::contains))
            assertTrue(expectedTargetColumns.all(columns::contains))
            assertTrue(expectedSyncYomiColumns.all(columns::contains))
            assertEquals(expectedSyncYomiTriggers, readTriggers(fixtureDatabase))
            assertTrue("EXTENSIONSTORE" in readTables(fixtureDatabase))
            assertTrue("EXTENSION_ACTIVATION_MARKER" in readTables(fixtureDatabase))
            assertTrue("EXTENSION_STORE_ENTRY" in readTables(fixtureDatabase))
            assertTrue("EXTENSION_STORE_SOURCE" in readTables(fixtureDatabase))
        } finally {
            TransactionManager.closeAndUnregister(fixtureDatabase)
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }

    private fun loadMigrationLine() =
        loadMigrationsFrom(
            "suwayomi.tachidesk.server.database.migration",
            ServerConfig::class.java,
        )

    private fun createFixtureDatabase(prefix: String): Database {
        val fixtureName = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
        return Database.connect(
            "jdbc:h2:mem:$fixtureName;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            databaseConfig =
                DatabaseConfig {
                    useNestedTransactions = true
                    @OptIn(ExperimentalKeywordApi::class)
                    preserveKeywordCasing = false
                },
        )
    }

    private fun insertReleasedKomuData(database: Database) {
        transaction(database) {
            exec(
                """
                INSERT INTO EXTENSION (
                    APK_NAME, REPO, NAME, PKG_NAME, VERSION_NAME,
                    VERSION_CODE, LANG, IS_NSFW, IS_INSTALLED, BASE_URL,
                    SOURCE_LANGS, IS_CUSTOM_PINNED
                ) VALUES (
                    'fixture.apk', 'https://repo.invalid/index.min.json',
                    'Fixture', 'app.komu.fixture', '1.4.7',
                    7, 'en', TRUE, TRUE, 'https://fixture.invalid',
                    'en,ja', TRUE
                )
                """.trimIndent(),
            )
            exec(
                "INSERT INTO GLOBALMETA (\"KEY\", \"VALUE\") " +
                    "VALUES ('fixture-key', 'fixture-value')",
            )
        }
    }

    private fun assertModernWritesUseLegacyDefaults(database: Database) {
        transaction(database) {
            exec(
                """
                INSERT INTO EXTENSION (
                    APK_NAME, NAME, PKG_NAME, VERSION_NAME, VERSION_CODE, LANG,
                    EXTENSION_LIB, CONTENT_WARNING, IS_INSTALLED
                ) VALUES (
                    'modern.apk', 'Modern', 'app.komu.modern', '1.4.7', 8, 'en',
                    '1.4', 0, TRUE
                )
                """.trimIndent(),
            )
            val extensionId =
                requireNotNull(
                    exec(
                        "SELECT ID FROM EXTENSION WHERE PKG_NAME = 'app.komu.modern'",
                    ) { rows ->
                        check(rows.next())
                        rows.getInt("ID")
                    },
                )
            exec(
                "INSERT INTO SOURCE (ID, \"NAME\", LANG, EXTENSION, CONTENT_WARNING) " +
                    "VALUES (999991, 'Modern Source', 'en', $extensionId, 0)",
            )

            val legacyFlags =
                requireNotNull(
                    exec(
                        """
                        SELECT E.IS_NSFW AS EXTENSION_NSFW, S.IS_NSFW AS SOURCE_NSFW
                        FROM EXTENSION E JOIN SOURCE S ON S.EXTENSION = E.ID
                        WHERE E.ID = $extensionId AND S.ID = 999991
                        """.trimIndent(),
                    ) { rows ->
                        check(rows.next())
                        rows.getBoolean("EXTENSION_NSFW") to rows.getBoolean("SOURCE_NSFW")
                    },
                )
            assertEquals(false to false, legacyFlags)
        }
    }

    private fun readLedger(database: Database): Map<Int, String> =
        transaction(database) {
            exec(
                "SELECT VERSION, NAME FROM MIGRATIONS " +
                    "WHERE VERSION BETWEEN 55 AND 64 ORDER BY VERSION",
            ) { rows ->
                buildMap {
                    while (rows.next()) {
                        put(rows.getInt("VERSION"), rows.getString("NAME"))
                    }
                }
            }
        }.orEmpty()

    private fun readColumns(database: Database): Set<Pair<String, String>> =
        transaction(database) {
            exec(
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS",
            ) { rows ->
                buildSet {
                    while (rows.next()) {
                        add(rows.getString("TABLE_NAME") to rows.getString("COLUMN_NAME"))
                    }
                }
            }
        }.orEmpty()

    private fun readTables(database: Database): Set<String> =
        transaction(database) {
            exec(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
            ) { rows ->
                buildSet {
                    while (rows.next()) {
                        add(rows.getString("TABLE_NAME"))
                    }
                }
            }
        }.orEmpty()

    private fun readTriggers(database: Database): Set<String> =
        transaction(database) {
            exec(
                "SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = 'PUBLIC'",
            ) { rows ->
                buildSet {
                    while (rows.next()) {
                        add(rows.getString("TRIGGER_NAME"))
                    }
                }
            }
        }.orEmpty()

    private fun readExtensionSnapshot(database: Database): ExtensionSnapshot =
        requireNotNull(
            transaction(database) {
                exec(
                    """
                    SELECT BASE_URL, SOURCE_LANGS, IS_CUSTOM_PINNED, REPO,
                           STORE_INDEX_URL, IS_NSFW, CONTENT_WARNING, EXTENSION_LIB
                    FROM EXTENSION WHERE PKG_NAME = 'app.komu.fixture'
                    """.trimIndent(),
                ) { rows ->
                    check(rows.next())
                    ExtensionSnapshot(
                        baseUrl = rows.getString("BASE_URL"),
                        sourceLangs = rows.getString("SOURCE_LANGS"),
                        isCustomPinned = rows.getBoolean("IS_CUSTOM_PINNED"),
                        repo = rows.getString("REPO"),
                        storeIndexUrl = rows.getString("STORE_INDEX_URL"),
                        isNsfw = rows.getBoolean("IS_NSFW"),
                        contentWarning = rows.getInt("CONTENT_WARNING"),
                        extensionLib = rows.getString("EXTENSION_LIB"),
                    )
                }
            },
        )

    private fun readMetadataSnapshot(database: Database): Pair<String, String> =
        requireNotNull(
            transaction(database) {
                exec(
                    "SELECT META_KEY, \"VALUE\" FROM GLOBALMETA " +
                        "WHERE META_KEY = 'fixture-key'",
                ) { rows ->
                    check(rows.next())
                    rows.getString("META_KEY") to rows.getString("VALUE")
                }
            },
        )

    private data class ExtensionSnapshot(
        val baseUrl: String,
        val sourceLangs: String,
        val isCustomPinned: Boolean,
        val repo: String,
        val storeIndexUrl: String,
        val isNsfw: Boolean,
        val contentWarning: Int,
        val extensionLib: String,
    )

    private companion object {
        val expectedLedger =
            mapOf(
                55 to "ExtensionBaseUrl",
                56 to "ExtensionSourceLangs",
                57 to "ExtensionCustomPinned",
                58 to "RenameMetaKeys",
                59 to "SyncYomi",
                60 to "AddNewExtensionApiFields",
                61 to "AddExtensionStore",
                62 to "AddJarUrlToExtensionTable",
                63 to "AddExtensionActivationMarker",
                64 to "AddExtensionStoreArtifactLedger",
            )

        val expectedCompatibilityColumns =
            setOf(
                "EXTENSION" to "BASE_URL",
                "EXTENSION" to "SOURCE_LANGS",
                "EXTENSION" to "IS_CUSTOM_PINNED",
                "EXTENSION" to "REPO",
                "EXTENSION" to "IS_NSFW",
                "SOURCE" to "IS_NSFW",
            )

        val expectedSyncYomiColumns =
            setOf(
                "MANGA" to "VERSION",
                "MANGA" to "IS_SYNCING",
                "MANGA" to "LAST_MODIFIED_AT",
                "CHAPTER" to "VERSION",
                "CHAPTER" to "IS_SYNCING",
                "CHAPTER" to "LAST_MODIFIED_AT",
                "CATEGORY" to "VERSION",
                "CATEGORY" to "UID",
                "CATEGORY" to "IS_SYNCING",
                "CATEGORY" to "LAST_MODIFIED_AT",
            )

        val expectedTargetColumns =
            setOf(
                "MANGA" to "MEMO",
                "CHAPTER" to "MEMO",
                "EXTENSION" to "STORE_INDEX_URL",
                "EXTENSION" to "EXTENSION_LIB",
                "EXTENSION" to "APK_URL",
                "EXTENSION" to "JAR_URL",
                "EXTENSION" to "CONTENT_WARNING",
                "SOURCE" to "CONTENT_WARNING",
                "EXTENSION_ACTIVATION_MARKER" to "MARKER_KEY",
                "EXTENSION_ACTIVATION_MARKER" to "PACKAGE_NAME",
                "EXTENSION_STORE_ENTRY" to "STORE_INDEX_URL",
                "EXTENSION_STORE_ENTRY" to "SIGNING_KEY",
                "EXTENSION_STORE_ENTRY" to "SNAPSHOT_TOKEN",
                "EXTENSION_STORE_ENTRY" to "PKG_NAME",
                "EXTENSION_STORE_ENTRY" to "JAR_URL",
                "EXTENSION_STORE_SOURCE" to "ENTRY",
                "EXTENSION_STORE_SOURCE" to "SOURCE_ID",
                "EXTENSION_STORE_SOURCE" to "HOME_URL",
            )

        val expectedSyncYomiTriggers =
            setOf(
                "UPDATE_MANGA_VERSION",
                "UPDATE_CHAPTER_AND_MANGA_VERSION",
                "UPDATE_MANGA_LAST_MODIFIED_AT",
                "INSERT_MANGA_LAST_MODIFIED_AT",
                "UPDATE_CHAPTER_LAST_MODIFIED_AT",
                "INSERT_CHAPTER_LAST_MODIFIED_AT",
                "INSERT_MANGA_CATEGORY_UPDATE_VERSION",
                "INSERT_CATEGORY_UID",
                "UPDATE_CATEGORY_VERSION",
            )
    }
}
