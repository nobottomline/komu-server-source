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

    private fun write(
        name: String,
        contents: String,
    ): Path = temporaryDirectory.resolve(name).also { Files.writeString(it, contents) }

    private fun <T> commit(
        staged: Path,
        active: Path,
        previousArtifact: Path?,
        commitMetadata: () -> T,
    ): T {
        val markers = mutableSetOf<String>()
        return ExtensionArtifactActivation.commit(
            packageName = "app.komu.fixture",
            databaseIdentity = DATABASE_IDENTITY,
            stagedArtifact = staged,
            activeArtifact = active,
            previousArtifact = previousArtifact,
            commitMetadata = { markerKey ->
                commitMetadata().also { markers += markerKey }
            },
            isCommitMarkerPresent = markers::contains,
            removeCommitMarker = markers::remove,
        )
    }

    private fun assertNoRollbackFiles() {
        Files.list(temporaryDirectory).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().contains(".rollback-") })
        }
    }

    private companion object {
        const val DATABASE_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
