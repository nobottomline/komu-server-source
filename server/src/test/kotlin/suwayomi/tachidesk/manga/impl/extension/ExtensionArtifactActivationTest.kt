package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExtensionArtifactActivationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `fresh activation publishes candidate before metadata commit`() {
        val staged = write("candidate.staging.jar", "new")
        val active = temporaryDirectory.resolve("active.jar")

        val result =
            commit(staged, active, previousArtifact = null) {
                assertEquals("new", Files.readString(active))
                "committed"
            }

        assertEquals("committed", result)
        assertEquals("new", Files.readString(active))
        assertFalse(Files.exists(staged))
        assertNoRollbackFiles()
    }

    @Test
    fun `metadata failure removes fresh activation`() {
        val staged = write("candidate.staging.jar", "new")
        val active = temporaryDirectory.resolve("active.jar")

        assertThrows(IllegalStateException::class.java) {
            commit(staged, active, previousArtifact = null) {
                throw IllegalStateException("database rejected candidate")
            }
        }

        assertFalse(Files.exists(active))
        assertFalse(Files.exists(staged))
        assertNoRollbackFiles()
    }

    @Test
    fun `metadata failure restores same-name installed artifact`() {
        val active = write("active.jar", "old")
        val staged = write("candidate.staging.jar", "new")

        assertThrows(IllegalStateException::class.java) {
            commit(staged, active, previousArtifact = active) {
                assertEquals("new", Files.readString(active))
                throw IllegalStateException("database rejected candidate")
            }
        }

        assertEquals("old", Files.readString(active))
        assertNoRollbackFiles()
    }

    @Test
    fun `successful different-name activation retires previous artifact only after commit`() {
        val previous = write("old.jar", "old")
        val staged = write("candidate.staging.jar", "new")
        val active = temporaryDirectory.resolve("new.jar")

        commit(staged, active, previous) {
            assertTrue(Files.exists(previous))
            assertEquals("new", Files.readString(active))
        }

        assertFalse(Files.exists(previous))
        assertEquals("new", Files.readString(active))
        assertNoRollbackFiles()
    }

    @Test
    fun `different-name metadata failure preserves previous artifact`() {
        val previous = write("old.jar", "old")
        val staged = write("candidate.staging.jar", "new")
        val active = temporaryDirectory.resolve("new.jar")

        assertThrows(IllegalStateException::class.java) {
            commit(staged, active, previous) {
                throw IllegalStateException("database rejected candidate")
            }
        }

        assertEquals("old", Files.readString(previous))
        assertFalse(Files.exists(active))
        assertNoRollbackFiles()
    }

    @Test
    fun `unconfirmed durability keeps the journal and the previous artifact for recovery`() {
        val previous = write("old.jar", "old")
        val staged = write("candidate.staging.jar", "new")
        val active = temporaryDirectory.resolve("new.jar")
        val markers = mutableSetOf<String>()

        val result =
            commit(staged, active, previous, markers, confirmDurable = { throw java.io.IOException("sync failed") }) {
                "committed"
            }

        assertEquals("committed", result)
        assertEquals("old", Files.readString(previous), "the previous artifact outlives an unconfirmed commit")
        assertEquals("new", Files.readString(active))
        val journal = ExtensionActivationJournal(temporaryDirectory)
        assertEquals(1, journal.loadAll().size, "the journal is kept")
        assertEquals(1, markers.size, "the marker is kept")

        // Startup recovery decides from the marker.
        val report =
            journal.recoverAll(DATABASE_IDENTITY, { key -> "app.komu.fixture".takeIf { key in markers } }, { markers.remove(it) })
        assertEquals(1, report.finalized)
        assertFalse(Files.exists(previous))
        assertTrue(journal.loadAll().isEmpty())
    }

    @Test
    fun `unconfirmed durability keeps the rollback copy of a same-name artifact`() {
        val active = write("active.jar", "old")
        val staged = write("candidate.staging.jar", "new")

        commit(staged, active, previousArtifact = active, confirmDurable = { throw java.io.IOException("sync failed") }) {}

        assertEquals("new", Files.readString(active))
        Files.list(temporaryDirectory).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().contains(".rollback-") }, "the rollback copy is kept")
        }
        assertEquals(1, ExtensionActivationJournal(temporaryDirectory).loadAll().size)
    }

    @Test
    fun `cleanup runs only after the commit was confirmed durable`() {
        val previous = write("old.jar", "old")
        val staged = write("candidate.staging.jar", "new")
        val active = temporaryDirectory.resolve("new.jar")
        var previousAtConfirmation: Boolean? = null

        commit(staged, active, previous, confirmDurable = { previousAtConfirmation = Files.exists(previous) }) {}

        assertEquals(true, previousAtConfirmation)
        assertFalse(Files.exists(previous))
        assertNoRollbackFiles()
    }

    private fun write(
        name: String,
        contents: String,
    ): Path = temporaryDirectory.resolve(name).also { Files.writeString(it, contents) }

    private fun <T> commit(
        staged: Path,
        active: Path,
        previousArtifact: Path?,
        markers: MutableSet<String> = mutableSetOf(),
        confirmDurable: () -> Unit = {},
        commitMetadata: () -> T,
    ): T =
        ExtensionArtifactActivation.commit(
            packageName = "app.komu.fixture",
            databaseIdentity = DATABASE_IDENTITY,
            stagedArtifact = staged,
            activeArtifact = active,
            previousArtifact = previousArtifact,
            commitMetadata = { markerKey ->
                commitMetadata().also { markers += markerKey }
            },
            isCommitMarkerPresent = markers::contains,
            removeCommitMarker = { markers.remove(it) },
            confirmDurable = confirmDurable,
        )

    private fun assertNoRollbackFiles() {
        Files.list(temporaryDirectory).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().contains(".rollback-") })
        }
    }

    private companion object {
        const val DATABASE_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
