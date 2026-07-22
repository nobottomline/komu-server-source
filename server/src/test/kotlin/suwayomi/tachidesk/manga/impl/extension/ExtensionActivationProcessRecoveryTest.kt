package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

class ExtensionActivationProcessRecoveryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `prepared crash is rolled back by a new process`() {
        val root = scenarioRoot(ExtensionActivationCrashFixture.Scenario.PREPARED)
        setupAndCrash(root, ExtensionActivationCrashFixture.Scenario.PREPARED)

        val recovery = recover(root)

        assertSuccessfulRollback(root, recovery)
    }

    @Test
    fun `swap-pending crash before publication is rolled back by a new process`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.FILE_SWAP_PENDING_BEFORE_PUBLISH
        val root = scenarioRoot(scenario)
        setupAndCrash(root, scenario)

        val recovery = recover(root)

        assertSuccessfulRollback(root, recovery)
    }

    @Test
    fun `swap-pending crash after publication restores old-good bytes in a new process`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.FILE_SWAP_PENDING_AFTER_PUBLISH
        val root = scenarioRoot(scenario)
        setupAndCrash(root, scenario)
        assertEquals(ExtensionActivationCrashFixture.CANDIDATE_BYTES, readActive(root))

        val recovery = recover(root)

        assertSuccessfulRollback(root, recovery)
    }

    @Test
    fun `file-visible crash without marker restores old-good bytes in a new process`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.FILE_VISIBLE_NO_MARKER
        val root = scenarioRoot(scenario)
        setupAndCrash(root, scenario)
        assertEquals(ExtensionActivationCrashFixture.CANDIDATE_BYTES, readActive(root))

        val recovery = recover(root)

        assertSuccessfulRollback(root, recovery)
    }

    @Test
    fun `file-visible crash with durable marker finalizes new-good bytes in a new process`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.FILE_VISIBLE_COMMITTED
        val root = scenarioRoot(scenario)
        setupAndCrash(root, scenario)
        val markerKey = pendingEntry(root).record.markerKey
        assertEquals(ExtensionActivationCrashFixture.PACKAGE_NAME, markerOwner(root, markerKey))

        val recovery = recover(root)

        assertSuccessfulFinalize(root, markerKey, recovery)
    }

    @Test
    fun `db-committed crash finalizes new-good bytes in a new process`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.DB_COMMITTED
        val root = scenarioRoot(scenario)
        setupAndCrash(root, scenario)
        val entry = pendingEntry(root)
        assertEquals(ExtensionActivationJournal.State.DB_COMMITTED, entry.record.state)

        val recovery = recover(root)

        assertSuccessfulFinalize(root, entry.record.markerKey, recovery)
    }

    @Test
    fun `foreign marker owner fails closed across process restart`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.MARKER_OWNER_MISMATCH
        val root = scenarioRoot(scenario)
        setupAndCrash(root, scenario)
        val entry = pendingEntry(root)

        val recovery = recover(root)

        assertNotEquals(0, recovery.exitCode)
        assertTrue(recovery.output.contains("marker belongs to a different package"), recovery.output)
        assertEquals(ExtensionActivationCrashFixture.CANDIDATE_BYTES, readActive(root))
        assertEquals(ExtensionActivationCrashFixture.FOREIGN_PACKAGE_NAME, markerOwner(root, entry.record.markerKey))
        assertRecoveryEvidenceRetained(root)
    }

    @Test
    fun `database identity mismatch fails closed across process restart`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.PREPARED
        val root = scenarioRoot("database-identity-mismatch")
        setupAndCrash(root, scenario)

        val recovery = recover(root, ExtensionActivationCrashFixture.OTHER_DATABASE_IDENTITY)

        assertNotEquals(0, recovery.exitCode)
        assertTrue(recovery.output.contains("different database configuration"), recovery.output)
        assertEquals(ExtensionActivationCrashFixture.OLD_BYTES, readActive(root))
        assertTrue(Files.exists(root.resolve(ExtensionActivationCrashFixture.STAGED_NAME)))
        assertRecoveryEvidenceRetained(root)
    }

    @Test
    fun `unknown active bytes fail closed across process restart`() {
        val scenario = ExtensionActivationCrashFixture.Scenario.UNKNOWN_ACTIVE_BYTES
        val root = scenarioRoot(scenario)
        setupAndCrash(root, scenario)
        assertEquals(ExtensionActivationCrashFixture.UNKNOWN_BYTES, readActive(root))

        val recovery = recover(root)

        assertNotEquals(0, recovery.exitCode)
        assertTrue(recovery.output.contains("matches neither candidate nor previous digest"), recovery.output)
        assertEquals(ExtensionActivationCrashFixture.UNKNOWN_BYTES, readActive(root))
        assertRecoveryEvidenceRetained(root)
    }

    private fun setupAndCrash(
        root: Path,
        scenario: ExtensionActivationCrashFixture.Scenario,
    ) {
        val result = runFixture("setup", root, scenario.name)
        assertEquals(ExtensionActivationCrashFixture.INTENTIONAL_CRASH_EXIT, result.exitCode, result.output)
        assertTrue(result.output.contains("fixture-ready scenario=$scenario"), result.output)
        assertEquals(1, ExtensionActivationJournal(root).loadAll().size)
        assertEquals(1, leaseCount(root))
    }

    private fun recover(
        root: Path,
        databaseIdentity: String = ExtensionActivationCrashFixture.DATABASE_IDENTITY,
    ): ProcessResult = runFixture("recover", root, databaseIdentity)

    private fun assertSuccessfulRollback(
        root: Path,
        recovery: ProcessResult,
    ) {
        assertEquals(0, recovery.exitCode, recovery.output)
        assertTrue(recovery.output.contains("rolledBack=1 finalized=0"), recovery.output)
        assertEquals(ExtensionActivationCrashFixture.OLD_BYTES, readActive(root))
        assertFalse(Files.exists(root.resolve(ExtensionActivationCrashFixture.STAGED_NAME)))
        assertRecoveryCleared(root)
    }

    private fun assertSuccessfulFinalize(
        root: Path,
        markerKey: String,
        recovery: ProcessResult,
    ) {
        assertEquals(0, recovery.exitCode, recovery.output)
        assertTrue(recovery.output.contains("rolledBack=0 finalized=1"), recovery.output)
        assertEquals(ExtensionActivationCrashFixture.CANDIDATE_BYTES, readActive(root))
        assertFalse(Files.exists(root.resolve(ExtensionActivationCrashFixture.PREVIOUS_NAME)))
        assertEquals(null, markerOwner(root, markerKey))
        assertRecoveryCleared(root)
    }

    private fun assertRecoveryCleared(root: Path) {
        assertTrue(ExtensionActivationJournal(root).loadAll().isEmpty())
        assertEquals(0, leaseCount(root))
    }

    private fun assertRecoveryEvidenceRetained(root: Path) {
        assertEquals(1, ExtensionActivationJournal(root).loadAll().size)
        assertEquals(1, leaseCount(root))
    }

    private fun pendingEntry(root: Path): ExtensionActivationJournal.Entry = ExtensionActivationJournal(root).loadAll().single()

    private fun readActive(root: Path): String = Files.readString(root.resolve(ExtensionActivationCrashFixture.ACTIVE_NAME))

    private fun markerOwner(
        root: Path,
        markerKey: String,
    ): String? {
        Class.forName("org.h2.Driver")
        val databasePath = root.resolve("activation-marker-db").toAbsolutePath().normalize()
        return DriverManager
            .getConnection("jdbc:h2:file:$databasePath;DB_CLOSE_ON_EXIT=FALSE")
            .use { connection ->
                connection
                    .prepareStatement(
                        "SELECT package_name FROM extension_activation_marker WHERE marker_key = ?",
                    ).use { statement ->
                        statement.setString(1, markerKey)
                        statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
                    }
            }
    }

    private fun leaseCount(root: Path): Long =
        Files.list(root.resolve(".activation-journal")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".lock") }.count()
        }

    private fun scenarioRoot(scenario: Any): Path = temporaryDirectory.resolve(scenario.toString().lowercase().replace('_', '-'))

    private fun runFixture(
        command: String,
        root: Path,
        value: String,
    ): ProcessResult {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                javaExecutable,
                "-Dfile.encoding=UTF-8",
                "-cp",
                childClasspath(),
                ExtensionActivationCrashFixture::class.java.name,
                command,
                root.toString(),
                value,
            ).redirectErrorStream(true)
                .start()
        val terminated = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!terminated) {
            process.destroy()
            if (!process.waitFor(PROCESS_TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROCESS_TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        }
        assertTrue(terminated, "Child JVM did not terminate")
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.exitValue(), output)
    }

    private fun childClasspath(): String {
        val entries = linkedSetOf<String>()
        val systemClasspath = System.getProperty("java.class.path")
        systemClasspath
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .forEach(entries::add)
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                loader.urLs
                    .filter { it.protocol == "file" }
                    .map { Path.of(it.toURI()).toString() }
                    .forEach(entries::add)
            }
            loader = loader.parent
        }
        require(entries.isNotEmpty()) { "Unable to resolve child JVM classpath" }
        return entries.joinToString(File.pathSeparator)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 20L
        const val PROCESS_TERMINATION_GRACE_SECONDS = 2L
    }
}
