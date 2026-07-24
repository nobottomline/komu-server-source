package suwayomi.tachidesk.server.database

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class H2MigrationOfflineTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `legacy database migrates offline with immutable rollback and idempotent retry`() {
        createLegacyDatabase()

        H2Migration.migrate(
            rootDir = root.toString(),
            h2Old = OLD_VERSION,
            h2New = NEW_VERSION,
        )

        val database = root.resolve("database.mv.db")
        val backup = root.resolve("database.mv.db.200.backup")
        val script = root.resolve("database.200.sql")
        assertTrue(database.exists())
        assertTrue(backup.exists())
        assertTrue(script.exists())
        assertFalse(root.resolve("bin/h2-migration-tools/h2-1.4.200.bin").exists())
        assertEquals(FIXTURE_VALUE, readCurrentValue(database))
        assertEquals(FIXTURE_VALUE, readLegacyValue(backup))
        assertTrue(script.readText().contains("TIMESTAMP(9)"))

        val rollbackHash = sha256(backup)
        H2Migration.migrate(
            rootDir = root.toString(),
            h2Old = OLD_VERSION,
            h2New = NEW_VERSION,
        )

        assertEquals(rollbackHash, sha256(backup))
        assertEquals(FIXTURE_VALUE, readCurrentValue(database))
    }

    private fun createLegacyDatabase() {
        withLegacyConnection(root.resolve("database")) { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE MIGRATIONS(
                        VERSION INT PRIMARY KEY,
                        NAME VARCHAR(400) NOT NULL,
                        EXECUTED_AT DATETIME(9) NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "INSERT INTO MIGRATIONS VALUES " +
                        "(57, 'ExtensionCustomPinned', CURRENT_TIMESTAMP())",
                )
                statement.execute(
                    "CREATE TABLE FIXTURE(ID INT PRIMARY KEY, VALUE VARCHAR(255) NOT NULL)",
                )
                statement.execute(
                    "INSERT INTO FIXTURE VALUES (1, '$FIXTURE_VALUE')",
                )
            }
        }
    }

    private fun readCurrentValue(database: Path): String =
        DriverManager
            .getConnection(
                "jdbc:h2:${database.toString().removeSuffix(".mv.db")};" +
                    "ACCESS_MODE_DATA=r;IFEXISTS=TRUE",
                "",
                "",
            ).use(::readFixtureValue)

    private fun readLegacyValue(database: Path): String {
        val base = database.resolveSibling("rollback-database")
        Files.copy(database, Path("$base.mv.db"))
        return withLegacyConnection(base, ::readFixtureValue)
    }

    private fun <T> withLegacyConnection(
        databaseBase: Path,
        block: (Connection) -> T,
    ): T {
        val legacyDriver =
            requireNotNull(javaClass.getResource("/h2-migration/h2-1.4.200.bin")) {
                "Missing pinned legacy H2 test resource"
            }
        URLClassLoader(
            arrayOf(legacyDriver),
            ClassLoader.getPlatformClassLoader(),
        ).use { classLoader ->
            val driver =
                classLoader
                    .loadClass("org.h2.Driver")
                    .getDeclaredConstructor()
                    .newInstance() as Driver
            val connection =
                requireNotNull(
                    driver.connect(
                        "jdbc:h2:$databaseBase;IFEXISTS=${databaseBase.exists()}",
                        Properties(),
                    ),
                )
            return connection.use(block)
        }
    }

    private fun readFixtureValue(connection: Connection): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT \"VALUE\" FROM FIXTURE WHERE ID = 1").use { rows ->
                assertTrue(rows.next())
                rows.getString(1)
            }
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val OLD_VERSION = "1.4.200"
        const val NEW_VERSION = "2.4.240"
        const val FIXTURE_VALUE = "offline-migration-survives"
    }
}
