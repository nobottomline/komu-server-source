package suwayomi.tachidesk.manga.impl.extension

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Child-process fixture. Setup intentionally terminates through [Runtime.halt]. */
object ExtensionActivationCrashFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 3) { "Expected command, root and scenario/database identity" }
        val command = arguments[0]
        val root = Path.of(arguments[1]).toAbsolutePath().normalize()
        when (command) {
            "setup" -> setupAndCrash(root, Scenario.valueOf(arguments[2]))
            "recover" -> recover(root, arguments[2])
            else -> error("Unknown crash fixture command: $command")
        }
    }

    private fun setupAndCrash(
        root: Path,
        scenario: Scenario,
    ) {
        Files.createDirectories(root)
        val journal = ExtensionActivationJournal(root)
        val samePath = scenario !in setOf(Scenario.FILE_VISIBLE_COMMITTED, Scenario.DB_COMMITTED)
        val previous = write(root, if (samePath) ACTIVE_NAME else PREVIOUS_NAME, OLD_BYTES)
        val active = if (samePath) previous else root.resolve(ACTIVE_NAME)
        val staged = write(root, STAGED_NAME, CANDIDATE_BYTES)
        val binding =
            ExtensionActivationJournal.AuthenticatedStoreBinding(
                packageName = PACKAGE_NAME,
                ledgerEntryID = 42,
                storeSnapshotToken = "123e4567-e89b-12d3-a456-426614174000",
                storeIndexUrl = "https://repo.invalid/index.json",
                storeSigningKey = "a".repeat(64),
                candidateSize = Files.size(staged),
                candidateSha256 = StagedArtifact.sha256(staged, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES),
                sourceIDs = listOf(101L),
            )
        var entry =
            journal.prepareAuthenticated(
                packageName = PACKAGE_NAME,
                databaseIdentity = DATABASE_IDENTITY,
                stagedArtifact = staged,
                activeArtifact = active,
                previousArtifact = previous,
                storeTrustBinding = binding,
            )

        when (scenario) {
            Scenario.PREPARED -> {}

            Scenario.FILE_SWAP_PENDING_BEFORE_PUBLISH -> {
                entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
            }

            Scenario.FILE_SWAP_PENDING_AFTER_PUBLISH -> {
                entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
                journal.publish(entry)
            }

            Scenario.FILE_VISIBLE_NO_MARKER,
            Scenario.FILE_VISIBLE_COMMITTED,
            Scenario.DB_COMMITTED,
            Scenario.MARKER_OWNER_MISMATCH,
            Scenario.UNKNOWN_ACTIVE_BYTES,
            -> {
                entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
                journal.publish(entry)
                entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_VISIBLE)
            }
        }

        when (scenario) {
            Scenario.FILE_VISIBLE_COMMITTED -> {
                insertMarker(root, entry.record.markerKey, PACKAGE_NAME)
            }

            Scenario.DB_COMMITTED -> {
                insertMarker(root, entry.record.markerKey, PACKAGE_NAME)
                entry = journal.transition(entry, ExtensionActivationJournal.State.DB_COMMITTED)
            }

            Scenario.MARKER_OWNER_MISMATCH -> {
                insertMarker(root, entry.record.markerKey, FOREIGN_PACKAGE_NAME)
            }

            Scenario.UNKNOWN_ACTIVE_BYTES -> {
                Files.writeString(active, UNKNOWN_BYTES)
            }

            else -> {
                initializeMarkerDatabase(root)
            }
        }

        println("fixture-ready scenario=$scenario state=${entry.record.state} marker=${entry.record.markerKey}")
        System.out.flush()
        Runtime.getRuntime().halt(INTENTIONAL_CRASH_EXIT)
    }

    private fun recover(
        root: Path,
        databaseIdentity: String,
    ) {
        val report =
            ExtensionActivationJournal(root).recoverAll(
                databaseIdentity = databaseIdentity,
                databaseMarkerOwner = { markerKey -> markerOwner(root, markerKey) },
                removeDatabaseMarker = { markerKey -> removeMarker(root, markerKey) },
            )
        println("recovery-complete rolledBack=${report.rolledBack} finalized=${report.finalized}")
    }

    private fun initializeMarkerDatabase(root: Path) {
        openMarkerDatabase(root).use { connection -> checkpoint(connection) }
    }

    private fun insertMarker(
        root: Path,
        markerKey: String,
        packageName: String,
    ) {
        val connection = openMarkerDatabase(root)
        connection.autoCommit = false
        connection
            .prepareStatement(
                "INSERT INTO extension_activation_marker(marker_key, package_name) VALUES (?, ?)",
            ).use { statement ->
                statement.setString(1, markerKey)
                statement.setString(2, packageName)
                statement.executeUpdate()
            }
        connection.commit()
        checkpoint(connection)
        // Deliberately do not close: Runtime.halt below models abrupt process loss
        // after the database has made its marker durable.
    }

    private fun markerOwner(
        root: Path,
        markerKey: String,
    ): String? =
        openMarkerDatabase(root).use { connection ->
            connection
                .prepareStatement(
                    "SELECT package_name FROM extension_activation_marker WHERE marker_key = ?",
                ).use { statement ->
                    statement.setString(1, markerKey)
                    statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
                }
        }

    private fun removeMarker(
        root: Path,
        markerKey: String,
    ) {
        openMarkerDatabase(root).use { connection ->
            connection
                .prepareStatement(
                    "DELETE FROM extension_activation_marker WHERE marker_key = ?",
                ).use { statement ->
                    statement.setString(1, markerKey)
                    statement.executeUpdate()
                }
            checkpoint(connection)
        }
    }

    private fun openMarkerDatabase(root: Path): Connection {
        Class.forName("org.h2.Driver")
        val databasePath = root.resolve("activation-marker-db").toAbsolutePath().normalize()
        return DriverManager
            .getConnection("jdbc:h2:file:$databasePath;DB_CLOSE_ON_EXIT=FALSE")
            .also { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS extension_activation_marker(
                            marker_key VARCHAR(64) PRIMARY KEY,
                            package_name VARCHAR(255) NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
            }
    }

    private fun checkpoint(connection: Connection) {
        connection.createStatement().use { statement -> statement.execute("CHECKPOINT SYNC") }
    }

    private fun write(
        root: Path,
        name: String,
        contents: String,
    ): Path = root.resolve(name).also { Files.writeString(it, contents) }

    enum class Scenario {
        PREPARED,
        FILE_SWAP_PENDING_BEFORE_PUBLISH,
        FILE_SWAP_PENDING_AFTER_PUBLISH,
        FILE_VISIBLE_NO_MARKER,
        FILE_VISIBLE_COMMITTED,
        DB_COMMITTED,
        MARKER_OWNER_MISMATCH,
        UNKNOWN_ACTIVE_BYTES,
    }

    const val INTENTIONAL_CRASH_EXIT = 86
    const val DATABASE_IDENTITY = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    const val OTHER_DATABASE_IDENTITY = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    const val PACKAGE_NAME = "app.komu.process.fixture"
    const val FOREIGN_PACKAGE_NAME = "app.komu.process.foreign"
    const val ACTIVE_NAME = "active.jar"
    const val PREVIOUS_NAME = "previous.jar"
    const val STAGED_NAME = "candidate.staging.jar"
    const val OLD_BYTES = "old-good"
    const val CANDIDATE_BYTES = "new-good"
    const val UNKNOWN_BYTES = "unknown-tampered"
}
