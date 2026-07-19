package suwayomi.tachidesk.manga.impl.extension

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import java.nio.file.Files
import java.nio.file.Path

class ExtensionJarPreflightPipelineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `successful preflight returns immutable owned candidate without publishing state`() =
        runBlocking {
            val events = mutableListOf<String>()
            val callerSources = mutableListOf(source())
            val pipeline = pipeline(FakeTransport(temporaryDirectory, events), events)

            val candidate =
                pipeline.prepare(
                    extensionInfo(callerSources),
                    SIGNING_KEY.uppercase(),
                    temporaryDirectory,
                    mapOf(SOURCE_ID to PACKAGE_NAME),
                )

            callerSources.clear()
            assertEquals(listOf("download", "inspect", "shadow"), events)
            assertEquals(1, candidate.expected.sources.size)
            assertEquals(SOURCE_ID, candidate.sources.single().id)
            assertEquals("all", candidate.extensionLanguage)
            assertThrows(UnsupportedOperationException::class.java) {
                (candidate.sources as MutableList<*>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (candidate.expected.sources as MutableList<*>).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (candidate.metadata.signature.verifiedEntryNames as MutableSet<*>).clear()
            }
            candidate.assertIntegrity()
            assertTrue(Files.isRegularFile(candidate.path))
            candidate.close()
            assertFalse(Files.exists(candidate.path))
            candidate.close()
        }

    @Test
    fun `inspector failure closes staged artifact and prevents runtime probe`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(temporaryDirectory, events)
        val pipeline =
            ExtensionJarPreflightPipeline(
                transport = transport,
                artifactInspector =
                    ExtensionJarPreflightPipeline.ArtifactInspector { _, _, _ ->
                        events += "inspect"
                        throw SecurityException("wrong signer")
                    },
                shadowValidator =
                    ExtensionJarPreflightPipeline.ShadowValidator { _, _, _, _ ->
                        error("runtime probe must not run")
                    },
            )

        assertThrows(SecurityException::class.java) {
            runBlocking { pipeline.prepare(extensionInfo(), SIGNING_KEY, temporaryDirectory, emptyMap()) }
        }
        assertEquals(listOf("download", "inspect"), events)
        assertFalse(Files.exists(transport.lastPath))
    }

    @Test
    fun `runtime probe failure closes authenticated staged artifact`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(temporaryDirectory, events)
        val pipeline =
            ExtensionJarPreflightPipeline(
                transport = transport,
                artifactInspector = inspector(events),
                shadowValidator =
                    ExtensionJarPreflightPipeline.ShadowValidator { _, _, _, _ ->
                        events += "shadow"
                        throw IllegalStateException("source collision")
                    },
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { pipeline.prepare(extensionInfo(), SIGNING_KEY, temporaryDirectory, emptyMap()) }
        }
        assertEquals(listOf("download", "inspect", "shadow"), events)
        assertFalse(Files.exists(transport.lastPath))
    }

    @Test
    fun `invalid URL key and duplicate sources fail before transport`() {
        val invalidInputs =
            listOf(
                extensionInfo().copy(jarUrl = "http://cdn.invalid/fixture.jar") to SIGNING_KEY,
                extensionInfo() to "not-a-key",
                extensionInfo(listOf(source(), source())) to SIGNING_KEY,
            )

        invalidInputs.forEach { (expected, key) ->
            val events = mutableListOf<String>()
            val pipeline = pipeline(FakeTransport(temporaryDirectory, events), events)
            assertThrows(RuntimeException::class.java) {
                runBlocking { pipeline.prepare(expected, key, temporaryDirectory, emptyMap()) }
            }
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `same-size artifact mutation after inspection is rejected before runtime probe`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(temporaryDirectory, events)
        val pipeline =
            ExtensionJarPreflightPipeline(
                transport = transport,
                artifactInspector =
                    ExtensionJarPreflightPipeline.ArtifactInspector { path, _, _ ->
                        events += "inspect"
                        Files.writeString(path, "tampered!")
                        artifactMetadata()
                    },
                shadowValidator =
                    ExtensionJarPreflightPipeline.ShadowValidator { _, _, _, _ ->
                        error("runtime probe must not observe mutated bytes")
                    },
            )

        assertThrows(SecurityException::class.java) {
            runBlocking { pipeline.prepare(extensionInfo(), SIGNING_KEY, temporaryDirectory, emptyMap()) }
        }
        assertEquals(listOf("download", "inspect"), events)
        assertFalse(Files.exists(transport.lastPath))
    }

    @Test
    fun `artifact mutation during runtime probe is rejected before candidate return`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(temporaryDirectory, events)
        val pipeline =
            ExtensionJarPreflightPipeline(
                transport = transport,
                artifactInspector = inspector(events),
                shadowValidator =
                    ExtensionJarPreflightPipeline.ShadowValidator { path, _, _, _ ->
                        events += "shadow"
                        Files.writeString(path, "tampered!")
                        probeResult()
                    },
            )

        assertThrows(SecurityException::class.java) {
            runBlocking { pipeline.prepare(extensionInfo(), SIGNING_KEY, temporaryDirectory, emptyMap()) }
        }
        assertEquals(listOf("download", "inspect", "shadow"), events)
        assertFalse(Files.exists(transport.lastPath))
    }

    @Test
    fun `transport kind confusion closes staged artifact before inspection`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(temporaryDirectory, events, returnedKind = ArtifactKind.APK)
        val pipeline = pipeline(transport, events)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { pipeline.prepare(extensionInfo(), SIGNING_KEY, temporaryDirectory, emptyMap()) }
        }
        assertEquals(listOf("download"), events)
        assertFalse(Files.exists(transport.lastPath))
    }

    @Test
    fun `injected probe output is independently rebound to immutable store snapshot`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(temporaryDirectory, events)
        val pipeline =
            ExtensionJarPreflightPipeline(
                transport = transport,
                artifactInspector = inspector(events),
                shadowValidator =
                    ExtensionJarPreflightPipeline.ShadowValidator { _, _, _, _ ->
                        events += "shadow"
                        probeResult().copy(
                            sources = listOf(ExtensionJarShadowValidator.SourceMetadata(SOURCE_ID, "Replacement", "all")),
                        )
                    },
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { pipeline.prepare(extensionInfo(), SIGNING_KEY, temporaryDirectory, emptyMap()) }
        }
        assertFalse(Files.exists(transport.lastPath))
    }

    private fun pipeline(
        transport: ExtensionArtifactTransport,
        events: MutableList<String>,
    ) = ExtensionJarPreflightPipeline(transport, inspector(events), validator(events))

    private fun inspector(events: MutableList<String>) =
        ExtensionJarPreflightPipeline.ArtifactInspector { _, expected, signingKey ->
            events += "inspect"
            assertEquals(PACKAGE_NAME, expected.pkgName)
            assertEquals(SIGNING_KEY, signingKey)
            artifactMetadata()
        }

    private fun validator(events: MutableList<String>) =
        ExtensionJarPreflightPipeline.ShadowValidator { _, _, expected, occupied ->
            events += "shadow"
            assertEquals(PACKAGE_NAME, expected.pkgName)
            assertThrows(UnsupportedOperationException::class.java) {
                (occupied as MutableMap<Long, String>).clear()
            }
            probeResult()
        }

    private class FakeTransport(
        private val root: Path,
        private val events: MutableList<String>,
        private val returnedKind: ArtifactKind = ArtifactKind.JAR,
    ) : ExtensionArtifactTransport {
        lateinit var lastPath: Path
            private set

        override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact {
            events += "download"
            assertEquals(JAR_URL, request.url)
            assertEquals(PACKAGE_NAME, request.packageName)
            assertEquals(ArtifactKind.JAR, request.kind)
            lastPath = Files.writeString(root.resolve("candidate-${System.nanoTime()}.jar"), "candidate")
            return StagedArtifact(
                path = lastPath,
                size = Files.size(lastPath),
                sha256 = CANDIDATE_SHA256,
                finalUrl = JAR_URL,
                kind = returnedKind,
            )
        }
    }

    private companion object {
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.fixture"
        const val SOURCE_ID = 1435103006121310663L
        const val SOURCE_NAME = "Fixture"
        const val JAR_URL = "https://cdn.invalid/fixture.jar"
        const val SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val CANDIDATE_SHA256 = "dda18a0e21ae47c53b4309434cbc02ae8bf764fa83a6defbb719431242722aa7"

        fun source() =
            ExtensionSource(
                id = SOURCE_ID,
                name = SOURCE_NAME,
                lang = "all",
                homeUrl = "https://fixture.invalid",
                message = null,
                contentWarning = ContentWarning.SAFE,
            )

        fun extensionInfo(sources: List<ExtensionSource> = listOf(source())) =
            ExtensionInfo(
                storeIndexUrl = "https://repo.invalid/index.json",
                name = SOURCE_NAME,
                pkgName = PACKAGE_NAME,
                apkUrl = "https://cdn.invalid/fixture.apk",
                jarUrl = JAR_URL,
                iconUrl = "https://cdn.invalid/fixture.png",
                extensionLib = "1.6",
                versionCode = 1,
                versionName = "1.6.1",
                lang = "all",
                contentWarning = ContentWarning.SAFE,
                sources = sources,
            )

        fun artifactMetadata() =
            ExtensionJarArtifactInspector.Metadata(
                packageName = PACKAGE_NAME,
                versionCode = 1,
                versionName = "1.6.1",
                extensionLib = "1.6",
                name = SOURCE_NAME,
                classFQName = "$PACKAGE_NAME.ExtensionGenerated",
                contentWarning = ContentWarning.SAFE,
                signature =
                    ExtensionJarVerifier.Result(
                        signerFingerprint = SIGNING_KEY,
                        verifiedEntries = 1,
                        uncompressedBytes = 128,
                        verifiedEntryNames = setOf("${PACKAGE_NAME.replace('.', '/')}/ExtensionGenerated.class"),
                    ),
            )

        fun probeResult() =
            ExtensionJarShadowValidator.Result(
                sources = listOf(ExtensionJarShadowValidator.SourceMetadata(SOURCE_ID, SOURCE_NAME, "all")),
                extensionLanguage = "all",
            )
    }
}
