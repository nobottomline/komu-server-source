package suwayomi.tachidesk.server.database

import io.github.oshai.kotlinlogging.KotlinLogging
import org.h2.tools.RunScript
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists

/**
 * Offline H2 file-format migration for the embedded product.
 *
 * H2 requires an export with the old driver followed by an import with the
 * new driver. The old driver is packaged as a pinned inert resource; the
 * active server H2 dependency performs the import. No executable migration
 * tool or first-launch network download is required.
 */
object H2Migration {
    private val logger = KotlinLogging.logger {}

    private const val SUPPORTED_OLD_VERSION = "1.4.200"
    private const val SUPPORTED_NEW_VERSION = "2.4.240"
    private const val LEGACY_DRIVER_RESOURCE = "/h2-migration/h2-1.4.200.bin"
    private const val LEGACY_DRIVER_SHA256 = "3ad9ac4b6aae9cd9d3ac1c447465e1ed06019b851b893dd6a8d76ddb6d85bca6"
    private const val LEGACY_DRIVER_FILE = "h2-1.4.200.bin"

    fun migrate(
        rootDir: String,
        h2Old: String,
        h2New: String,
    ) {
        require(h2Old == SUPPORTED_OLD_VERSION && h2New == SUPPORTED_NEW_VERSION) {
            "Unsupported embedded H2 migration $h2Old -> $h2New"
        }

        val dbBase = Path(rootDir, "database")
        val mvStore = Path("$dbBase.mv.db")
        if (mvStore.notExists()) {
            logger.info { "No H2 database found. Skipping migration." }
            return
        }

        if (opensWithCurrentDriver(dbBase)) {
            logger.info {
                "H2 database already uses the current format. " +
                    "Skipping repeated $h2Old -> $h2New migration."
            }
            return
        }

        val toolsDir = Path(rootDir) / "bin" / "h2-migration-tools"
        toolsDir.createDirectories()
        val legacyDriver = toolsDir.resolve(LEGACY_DRIVER_FILE)

        try {
            materializeLegacyDriver(legacyDriver)
            verifyLegacyDatabase(legacyDriver, dbBase)

            val backup = Path("$dbBase.mv.db.${h2Old.substringAfterLast('.')}.backup")
            if (backup.notExists()) {
                mvStore.copyTo(backup)
                logger.info { "Created immutable H2 rollback backup: ${backup.absolutePathString()}" }
            } else {
                logger.info { "Preserving existing H2 rollback backup: ${backup.absolutePathString()}" }
            }

            val script = Path("$dbBase.${h2Old.substringAfterLast('.')}.sql")
            val candidateBase = Path("$dbBase.${h2New.substringAfterLast('.')}")
            val candidateStore = Path("$candidateBase.mv.db")
            script.deleteIfExists()
            candidateStore.deleteIfExists()

            exportWithLegacyDriver(
                legacyDriver = legacyDriver,
                dbBase = dbBase,
                script = script,
            )
            normalizeKnownLegacyTypes(script)
            importWithCurrentDriver(
                candidateBase = candidateBase,
                script = script,
            )
            check(opensWithCurrentDriver(candidateBase)) {
                "Migrated H2 candidate cannot be opened with $h2New"
            }

            replaceDatabaseAtomically(
                candidateStore = candidateStore,
                destinationStore = mvStore,
            )
            logger.info { "H2 migration $h2Old -> $h2New completed successfully." }
        } finally {
            legacyDriver.deleteIfExists()
        }
    }

    private fun materializeLegacyDriver(destination: Path) {
        val temporary = destination.resolveSibling("$LEGACY_DRIVER_FILE.tmp")
        temporary.deleteIfExists()

        try {
            val resource =
                requireNotNull(H2Migration::class.java.getResourceAsStream(LEGACY_DRIVER_RESOURCE)) {
                    "Missing embedded legacy H2 driver resource $LEGACY_DRIVER_RESOURCE"
                }
            resource.use { input ->
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
            }

            check(sha256(temporary) == LEGACY_DRIVER_SHA256) {
                "Embedded legacy H2 driver failed SHA-256 verification"
            }
            moveReplacing(temporary, destination)
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun verifyLegacyDatabase(
        legacyDriver: Path,
        dbBase: Path,
    ) {
        withLegacyConnection(legacyDriver, legacyReadOnlyUrl(dbBase)) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { rows ->
                    check(rows.next() && rows.getInt(1) == 1) {
                        "Legacy H2 verification query failed"
                    }
                }
            }
        }
    }

    private fun exportWithLegacyDriver(
        legacyDriver: Path,
        dbBase: Path,
        script: Path,
    ) {
        URLClassLoader(
            arrayOf(legacyDriver.toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { classLoader ->
            val scriptTool =
                classLoader
                    .loadClass("org.h2.tools.Script")
                    .getDeclaredConstructor()
                    .newInstance()
            val runTool =
                scriptTool.javaClass.getMethod(
                    "runTool",
                    Array<String>::class.java,
                )
            runTool.invoke(
                scriptTool,
                arrayOf(
                    "-url",
                    legacyReadOnlyUrl(dbBase),
                    "-user",
                    "",
                    "-password",
                    "",
                    "-script",
                    script.absolutePathString(),
                ),
            )
        }
    }

    private fun normalizeKnownLegacyTypes(script: Path) {
        val normalized = script.resolveSibling("${script.fileName}.normalized.tmp")
        normalized.deleteIfExists()
        Files.newBufferedReader(script, StandardCharsets.UTF_8).use { reader ->
            Files.newBufferedWriter(normalized, StandardCharsets.UTF_8).use { writer ->
                reader.forEachLine { line ->
                    writer.write(
                        line.replace(
                            "\"EXECUTED_AT\" DATETIME(9) NOT NULL",
                            "\"EXECUTED_AT\" TIMESTAMP(9) NOT NULL",
                        ),
                    )
                    writer.newLine()
                }
            }
        }
        moveReplacing(normalized, script)
    }

    private fun importWithCurrentDriver(
        candidateBase: Path,
        script: Path,
    ) {
        RunScript.execute(
            currentUrl(candidateBase),
            "",
            "",
            script.absolutePathString(),
            StandardCharsets.UTF_8,
            false,
        )
    }

    private fun opensWithCurrentDriver(dbBase: Path): Boolean =
        runCatching {
            DriverManager
                .getConnection(currentReadOnlyUrl(dbBase), "", "")
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT 1").use { rows ->
                            check(rows.next() && rows.getInt(1) == 1)
                        }
                    }
                }
        }.isSuccess

    private fun <T> withLegacyConnection(
        legacyDriver: Path,
        url: String,
        block: (Connection) -> T,
    ): T =
        URLClassLoader(
            arrayOf(legacyDriver.toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { classLoader ->
            val driver =
                classLoader
                    .loadClass("org.h2.Driver")
                    .getDeclaredConstructor()
                    .newInstance() as Driver
            val connection =
                requireNotNull(driver.connect(url, Properties())) {
                    "Legacy H2 driver rejected its own JDBC URL"
                }
            connection.use(block)
        }

    private fun replaceDatabaseAtomically(
        candidateStore: Path,
        destinationStore: Path,
    ) {
        check(candidateStore.exists()) {
            "H2 migration did not produce ${candidateStore.absolutePathString()}"
        }
        try {
            Files.move(
                candidateStore,
                destinationStore,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            logger.warn {
                "Atomic H2 replacement is unavailable; using same-directory replace with rollback backup"
            }
            Files.move(
                candidateStore,
                destinationStore,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun moveReplacing(
        source: Path,
        destination: Path,
    ) {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
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

    private fun legacyReadOnlyUrl(dbBase: Path): String = "jdbc:h2:${dbBase.absolutePathString()};ACCESS_MODE_DATA=r;IFEXISTS=TRUE"

    private fun currentReadOnlyUrl(dbBase: Path): String = "${currentUrl(dbBase)};ACCESS_MODE_DATA=r;IFEXISTS=TRUE"

    private fun currentUrl(dbBase: Path): String = "jdbc:h2:${dbBase.absolutePathString()}"
}
