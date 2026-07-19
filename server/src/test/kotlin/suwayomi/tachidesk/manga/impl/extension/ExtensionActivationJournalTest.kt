package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExtensionActivationJournalTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `prepared operation without database marker is discarded`() {
        val old = write("active.jar", "old")
        val staged = write("candidate.jar", "new")
        val journal = ExtensionActivationJournal(root)
        journal.prepare(PACKAGE_NAME, DATABASE_IDENTITY, staged, old, old)

        val report = journal.recoverAll(DATABASE_IDENTITY, { false }, {})

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

        ExtensionActivationJournal(root).recoverAll(DATABASE_IDENTITY, { false }, {})

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
                isDatabaseCommitted = markers::contains,
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

        ExtensionActivationJournal(root).recoverAll(DATABASE_IDENTITY, { false }, {})

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
            ExtensionActivationJournal(root).recoverAll(DATABASE_IDENTITY, { false }, {})
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
            ExtensionActivationJournal(root).recoverAll(OTHER_DATABASE_IDENTITY, { false }, {})
        }

        assertEquals("old", Files.readString(active))
        assertEquals("new", Files.readString(staged))
        assertEquals(1, journal.loadAll().size)
    }

    private fun write(
        name: String,
        contents: String,
    ): Path = root.resolve(name).also { Files.writeString(it, contents) }

    private companion object {
        const val PACKAGE_NAME = "app.komu.fixture"
        const val DATABASE_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_DATABASE_IDENTITY = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
