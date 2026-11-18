package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ExtensionActivationJournalTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `authenticated store binding survives durable round trip`() {
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        val binding = authenticatedBinding(staged)

        val entry =
            journal.prepareAuthenticated(
                PACKAGE_NAME,
                DATABASE_IDENTITY,
                staged,
                root.resolve("active.jar"),
                previousArtifact = null,
                storeTrustBinding = binding,
            )

        assertEquals(
            binding,
            journal
                .loadAll()
                .single()
                .record.storeTrustBinding,
        )
        journal.rollback(entry)
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `a journal moved with its data root is recovered when its identity is accepted`() {
        val previous = write("active.jar", "old")
        val staged = write("candidate.jar", "new")
        val legacyIdentity = OTHER_DATABASE_IDENTITY
        ExtensionActivationJournal(root).prepare(PACKAGE_NAME, legacyIdentity, staged, previous, previous)

        val journal = ExtensionActivationJournal(root)
        assertThrows(IllegalArgumentException::class.java) {
            journal.recoverAll(DATABASE_IDENTITY, { null }, {})
        }
        val report = journal.recoverAll({ it == legacyIdentity }, { null }, {})

        assertEquals(1, report.rolledBack)
        assertEquals("old", Files.readString(previous))
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `journal rejects artifact changed after authenticated preflight without leaking lease`() {
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        val binding = authenticatedBinding(staged)
        Files.writeString(staged, "bad")

        assertThrows(IllegalArgumentException::class.java) {
            journal.prepareAuthenticated(
                PACKAGE_NAME,
                DATABASE_IDENTITY,
                staged,
                root.resolve("active.jar"),
                previousArtifact = null,
                storeTrustBinding = binding,
            )
        }

        assertTrue(journal.loadAll().isEmpty())
        val replacement = write("replacement.jar", "new")
        val entry = journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, replacement, root.resolve("active.jar"), null)
        journal.rollback(entry)
    }

    @Test
    fun `atomic package lease permits only one concurrent pending activation`() {
        val first = write("first.jar", "first")
        val second = write("second.jar", "second")
        val journal = ExtensionActivationJournal(root)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts =
                listOf(first, second).map { staged ->
                    executor.submit<ExtensionActivationJournal.Entry> {
                        start.await()
                        journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, root.resolve("active.jar"), null)
                    }
                }
            start.countDown()

            val results = attempts.map { runCatching { it.get() } }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull()?.cause is PendingExtensionActivationException })
            assertEquals(1, journal.loadAll().size)

            journal.recoverAll(DATABASE_IDENTITY, { null }, {})
            assertTrue(journal.loadAll().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `pending journal cannot be bypassed when its lease file disappears`() {
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, root.resolve("active.jar"), null)
        Files.delete(singleLeaseFile())
        val competing = write("competing.jar", "other")

        assertThrows(PendingExtensionActivationException::class.java) {
            journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, competing, root.resolve("active.jar"), null)
        }

        assertEquals(1, journal.loadAll().size)
        assertTrue(Files.exists(competing))
        val report = journal.recoverAll(DATABASE_IDENTITY, { null }, {})
        assertEquals(1, report.rolledBack)
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `mismatched package lease blocks artifact mutation`() {
        val active = write("active.jar", "old")
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        val entry = journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, active, active)
        Files.writeString(
            singleLeaseFile(),
            "00000000-0000-0000-0000-000000000000\n$PACKAGE_NAME\n",
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        assertThrows(IllegalArgumentException::class.java) { journal.rollback(entry) }

        assertEquals("old", Files.readString(active))
        assertEquals("new", Files.readString(staged))
        assertEquals(1, journal.loadAll().size)
    }

    @Test
    fun `version one journal remains recoverable and receives a startup lease`() {
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        val entry = journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, root.resolve("active.jar"), null)
        val properties = readProperties(entry.path)
        properties.setProperty("formatVersion", "1")
        properties.remove("trustMode")
        writeProperties(entry.path, properties)

        assertEquals(
            null,
            journal
                .loadAll()
                .single()
                .record.storeTrustBinding,
        )
        val report = journal.recoverAll(DATABASE_IDENTITY, { null }, {})

        assertEquals(1, report.rolledBack)
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `partial authenticated binding is rejected fail closed`() {
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        val entry =
            journal.prepareAuthenticated(
                PACKAGE_NAME,
                DATABASE_IDENTITY,
                staged,
                root.resolve("active.jar"),
                previousArtifact = null,
                storeTrustBinding = authenticatedBinding(staged),
            )
        val properties = readProperties(entry.path)
        properties.remove("storeSigningKey")
        writeProperties(entry.path, properties)

        assertThrows(IllegalStateException::class.java) { journal.loadAll() }
        assertTrue(Files.exists(staged))
    }

    @Test
    fun `prepared operation without database marker is discarded`() {
        val old = write("active.jar", "old")
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, old, old)

        val report = journal.recoverAll(DATABASE_IDENTITY, { null }, {})

        assertEquals(1, report.rolledBack)
        assertEquals("old", Files.readString(old))
        assertFalse(Files.exists(staged))
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `crash after same-path publication rolls back without marker`() {
        val active = write("active.jar", "old")
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        var entry = journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, active, active)
        entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
        journal.publish(entry)

        ExtensionActivationJournal(root).recoverAll(DATABASE_IDENTITY, { null }, {})

        assertEquals("old", Files.readString(active))
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `database marker rolls committed operation forward from file-visible state`() {
        val previous = write("old.jar", "old")
        val staged = write("candidate.jar", "new")
        val active = root.resolve("new.jar")
        val journal = ExtensionActivationJournal(root)
        var entry = journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, active, previous)
        entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
        journal.publish(entry)
        entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_VISIBLE)
        val markers = mutableSetOf(entry.record.markerKey)

        val report =
            ExtensionActivationJournal(root).recoverAll(
                databaseIdentity = DATABASE_IDENTITY,
                databaseMarkerOwner = { markerKey -> PACKAGE_NAME.takeIf { markerKey in markers } },
                removeDatabaseMarker = markers::remove,
            )

        assertEquals(1, report.finalized)
        assertEquals("new", Files.readString(active))
        assertFalse(Files.exists(previous))
        assertTrue(markers.isEmpty())
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `different-path publication without marker preserves previous`() {
        val previous = write("old.jar", "old")
        val staged = write("candidate.jar", "new")
        val active = root.resolve("new.jar")
        val journal = ExtensionActivationJournal(root)
        var entry = journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, active, previous)
        entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
        journal.publish(entry)
        journal.transition(entry, ExtensionActivationJournal.State.FILE_VISIBLE)

        ExtensionActivationJournal(root).recoverAll(DATABASE_IDENTITY, { null }, {})

        assertEquals("old", Files.readString(previous))
        assertFalse(Files.exists(active))
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `unknown active bytes fail closed and retain journal`() {
        val active = write("active.jar", "old")
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        var entry = journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, active, active)
        entry = journal.transition(entry, ExtensionActivationJournal.State.FILE_SWAP_PENDING)
        journal.publish(entry)
        Files.writeString(active, "tampered")

        assertThrows(IllegalStateException::class.java) {
            ExtensionActivationJournal(root).recoverAll(DATABASE_IDENTITY, { null }, {})
        }

        assertEquals("tampered", Files.readString(active))
        assertEquals(1, journal.loadAll().size)
    }

    @Test
    fun `journal from another database fails closed without touching artifacts`() {
        val active = write("active.jar", "old")
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, active, active)

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionActivationJournal(root).recoverAll(OTHER_DATABASE_IDENTITY, { null }, {})
        }

        assertEquals("old", Files.readString(active))
        assertEquals("new", Files.readString(staged))
        assertEquals(1, journal.loadAll().size)
    }

    private fun write(
        name: String,
        contents: String,
    ): Path = root.resolve(name).also { Files.writeString(it, contents) }

    private fun authenticatedBinding(staged: Path) =
        ExtensionActivationJournal.AuthenticatedStoreBinding(
            packageName = PACKAGE_NAME,
            ledgerEntryID = 42,
            storeSnapshotToken = "123e4567-e89b-12d3-a456-426614174000",
            storeIndexUrl = "https://repo.invalid/index.json",
            storeSigningKey = "a".repeat(64),
            candidateSize = Files.size(staged),
            candidateSha256 = StagedArtifact.sha256(staged, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES),
            sourceIDs = listOf(101, 202),
        )

    private fun readProperties(path: Path) =
        Properties().apply {
            Files.newInputStream(path).use(::load)
        }

    private fun writeProperties(
        path: Path,
        properties: Properties,
    ) {
        Files
            .newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            .use { properties.store(it, null) }
    }

    private fun singleLeaseFile(): Path =
        Files
            .list(root.resolve(".activation-journal"))
            .use { paths -> paths.filter { it.fileName.toString().endsWith(".lock") }.toList().single() }

    private companion object {
        const val PACKAGE_NAME = "app.komu.fixture"
        const val DATABASE_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_DATABASE_IDENTITY = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
