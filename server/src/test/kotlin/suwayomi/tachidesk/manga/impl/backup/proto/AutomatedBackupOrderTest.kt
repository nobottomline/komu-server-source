package suwayomi.tachidesk.manga.impl.backup.proto

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomatedBackupOrderTest {
    @Test
    fun `old archives are pruned only after the new one was published`() {
        val events = mutableListOf<String>()
        val published = File("auto-new.tachibk")
        publishThenPrune(
            create = {
                events += "publish"
                published
            },
            prune = { keep ->
                events += "prune keeping ${keep.name}"
            },
        )
        assertEquals(listOf("publish", "prune keeping auto-new.tachibk"), events)
    }

    @Test
    fun `a failed backup prunes nothing`() {
        var pruned = false
        assertFailsWith<java.io.IOException> {
            publishThenPrune(
                create = { throw java.io.IOException("No space left on device") },
                prune = { pruned = true },
            )
        }
        assertEquals(false, pruned)
    }

    @Test
    fun `the directory is synced after the rename and before anything is pruned`(
        @TempDir directory: Path,
    ) {
        val events = mutableListOf<String>()
        val target = directory.resolve("auto-new.tachibk").toFile()
        publishThenPrune(
            create = {
                publishArchiveDurably(
                    target,
                    write = { it.write("archive".toByteArray()) },
                    syncDirectory = { synced ->
                        events += "sync ${synced.fileName} published=${target.isFile}"
                    },
                )
            },
            prune = { keep -> events += "prune keeping ${keep.name}" },
        )
        assertEquals(
            listOf("sync ${directory.fileName} published=true", "prune keeping auto-new.tachibk"),
            events,
        )
        assertEquals("archive", target.readText())
        assertEquals(listOf("auto-new.tachibk"), directory.toFile().list()!!.toList())
    }

    @Test
    fun `a failed directory sync prunes nothing`(
        @TempDir directory: Path,
    ) {
        val old = directory.resolve("auto-old.tachibk").toFile().apply { writeText("old") }
        var pruned = false
        assertFailsWith<IOException> {
            publishThenPrune(
                create = {
                    publishArchiveDurably(
                        directory.resolve("auto-new.tachibk").toFile(),
                        write = { it.write("archive".toByteArray()) },
                        syncDirectory = { throw IOException("Input/output error") },
                    )
                },
                prune = { pruned = true },
            )
        }
        assertEquals(false, pruned)
        assertEquals("old", old.readText())
    }

    @Test
    fun `the real directory sync accepts a directory`(
        @TempDir directory: Path,
    ) {
        syncDirectory(directory)
    }
}
