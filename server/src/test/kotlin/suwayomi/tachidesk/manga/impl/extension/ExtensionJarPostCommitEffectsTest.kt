package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.net.URLClassLoader
import java.nio.file.Path

class ExtensionJarPostCommitEffectsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `effects deduplicate artifacts and source IDs in deterministic order`() {
        val events = mutableListOf<String>()
        val active = temporaryDirectory.resolve("active.jar")
        val effects =
            ExtensionJarPostCommitEffects(
                closeClassLoader = { events += "close:${it.fileName}" },
                unregisterSource = { events += "source:$it" },
                clearAvailableUpdate = { events += "update:$it" },
                runtimeBoundary =
                    ExtensionJarPostCommitEffects.RuntimeBoundary { action ->
                        events += "boundary:start"
                        action()
                        events += "boundary:end"
                    },
            )

        val report =
            effects.apply(
                context(
                    active = active,
                    previous = active,
                    previousSources = listOf(30, 10, 30),
                    installedSources = listOf(20, 10),
                ),
            )

        assertEquals(
            listOf(
                "boundary:start",
                "close:active.jar",
                "source:10",
                "source:20",
                "source:30",
                "update:$PACKAGE_NAME",
                "boundary:end",
            ),
            events,
        )
        assertEquals(1, report.attemptedArtifacts)
        assertEquals(3, report.attemptedSources)
        assertTrue(report.isComplete)
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `one effect failure is reported without suppressing remaining committed cleanup`() {
        val events = mutableListOf<String>()
        val previous = temporaryDirectory.resolve("previous.jar")
        val active = temporaryDirectory.resolve("active.jar")
        val effects =
            ExtensionJarPostCommitEffects(
                closeClassLoader = {
                    events += "close:${it.fileName}"
                    if (it == previous) throw IllegalStateException("busy loader")
                },
                unregisterSource = {
                    events += "source:$it"
                    if (it == 20L) throw IllegalArgumentException("busy source")
                },
                clearAvailableUpdate = { events += "update:$it" },
            )

        val report = effects.apply(context(active, previous, listOf(10), listOf(20)))

        assertEquals(
            listOf("close:previous.jar", "close:active.jar", "source:10", "source:20", "update:$PACKAGE_NAME"),
            events,
        )
        assertFalse(report.isComplete)
        assertEquals(2, report.failures.size)
        assertEquals(
            listOf(
                ExtensionJarPostCommitEffects.Step.CLOSE_CLASS_LOADER,
                ExtensionJarPostCommitEffects.Step.UNREGISTER_SOURCE,
            ),
            report.failures.map { it.step },
        )
        assertEquals(listOf(previous.toString(), "20"), report.failures.map { it.subject })
    }

    @Test
    fun `retry-safe adapters converge when effects are applied repeatedly`() {
        val active = temporaryDirectory.resolve("active.jar")
        val previous = temporaryDirectory.resolve("previous.jar")
        val loaders = mutableSetOf(previous, active)
        val sources = mutableSetOf(10L, 20L)
        val updates = mutableSetOf(PACKAGE_NAME)
        val effects =
            ExtensionJarPostCommitEffects(
                closeClassLoader = { loaders.remove(it) },
                unregisterSource = { sources.remove(it) },
                clearAvailableUpdate = { updates.remove(it) },
            )
        val context = context(active, previous, listOf(10), listOf(20))

        val first = effects.apply(context)
        val second = effects.apply(context)

        assertTrue(first.isComplete)
        assertTrue(second.isComplete)
        assertTrue(loaders.isEmpty())
        assertTrue(sources.isEmpty())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `classloader close failure retains the exact loader for a later retry`() {
        val artifact = temporaryDirectory.resolve("retry.jar").toAbsolutePath().normalize()
        val key = artifact.toString()
        var closeCalls = 0
        val loader =
            object : URLClassLoader(emptyArray()) {
                override fun close() {
                    closeCalls += 1
                    if (closeCalls == 1) throw IllegalStateException("busy")
                    super.close()
                }
            }
        PackageTools.jarLoaderMap[key] = loader
        try {
            assertThrows(IllegalStateException::class.java) {
                PackageTools.closeExtensionClassLoader(artifact)
            }
            assertSame(loader, PackageTools.jarLoaderMap[key])

            assertTrue(PackageTools.closeExtensionClassLoader(artifact))
            assertFalse(PackageTools.jarLoaderMap.containsKey(key))
            assertEquals(2, closeCalls)
        } finally {
            PackageTools.jarLoaderMap.remove(key)
            runCatching { loader.close() }
        }
    }

    private fun context(
        active: Path,
        previous: Path?,
        previousSources: List<Long>,
        installedSources: List<Long>,
    ) = ExtensionJarPostCommitEffects.Context(
        packageName = PACKAGE_NAME,
        activeArtifact = active,
        previousArtifact = previous,
        previousSourceIDs = previousSources,
        installedSourceIDs = installedSources,
    )

    private companion object {
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.fixture"
    }
}
