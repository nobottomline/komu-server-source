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

class ExtensionJarPreflightCoordinatorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `coordinator preserves ownership token and returns only guarded operation owned staging`() =
        runBlocking {
            val events = mutableListOf<String>()
            val transport = FakeTransport(events, temporaryDirectory)
            val coordinator = coordinator(events, transport)

            val prepared = coordinator.prepare(PACKAGE_NAME, temporaryDirectory)

            assertEquals(listOf("read", "select", "download", "inspect", "shadow", "guard"), events)
            assertEquals(ENTRY_ID, prepared.ledgerEntryID)
            assertEquals(SNAPSHOT_TOKEN, prepared.storeSnapshotToken)
            assertEquals(PACKAGE_NAME, prepared.expected.pkgName)
            assertEquals(listOf(SOURCE_ID), prepared.sources.map { it.id })
            prepared.assertIntegrity()
            assertTrue(Files.isRegularFile(prepared.path))
            prepared.close()
            assertFalse(Files.exists(transport.lastPath))
            prepared.close()
        }

    @Test
    fun `ambiguous ownership fails before transport selection`() {
        var selectorCalls = 0
        val coordinator =
            ExtensionJarPreflightCoordinator(
                inputReader = { throw AmbiguousExtensionStoreOwnershipException(it, 2) },
                transportSelector = {
                    selectorCalls += 1
                    error("transport selector must not run")
                },
            )

        assertThrows(AmbiguousExtensionStoreOwnershipException::class.java) {
            runBlocking { coordinator.prepare(PACKAGE_NAME, temporaryDirectory) }
        }
        assertEquals(0, selectorCalls)
    }

    @Test
    fun `postflight ownership failure closes authenticated candidate`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(events, temporaryDirectory)
        val coordinator =
            coordinator(events, transport) { _, _ ->
                events += "guard"
                throw IllegalStateException("source owner changed")
            }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.prepare(PACKAGE_NAME, temporaryDirectory) }
        }
        assertEquals(listOf("read", "select", "download", "inspect", "shadow", "guard"), events)
        assertFalse(Files.exists(transport.lastPath))
    }

    @Test
    fun `artifact mutation during postflight fence is rejected and cleaned`() {
        val events = mutableListOf<String>()
        val transport = FakeTransport(events, temporaryDirectory)
        val coordinator =
            coordinator(events, transport) { _, _ ->
                events += "guard"
                Files.writeString(transport.lastPath, "tampered!")
            }

        assertThrows(SecurityException::class.java) {
            runBlocking { coordinator.prepare(PACKAGE_NAME, temporaryDirectory) }
        }
        assertFalse(Files.exists(transport.lastPath))
    }

    private fun coordinator(
        events: MutableList<String>,
        transport: ExtensionArtifactTransport,
        guard: (ExtensionJarPreflightInputSnapshot, Set<Long>) -> Unit = { _, sourceIDs ->
            events += "guard"
            assertEquals(setOf(SOURCE_ID), sourceIDs)
        },
    ) = ExtensionJarPreflightCoordinator(
        inputReader = {
            events += "read"
            snapshot()
        },
        transportSelector = {
            events += "select"
            transport
        },
        pipelineFactory = {
            ExtensionJarPreflightPipeline(
                transport = it,
                artifactInspector =
                    ExtensionJarPreflightPipeline.ArtifactInspector { _, expected, signingKey ->
                        events += "inspect"
                        assertEquals(PACKAGE_NAME, expected.pkgName)
                        assertEquals(SIGNING_KEY, signingKey)
                        artifactMetadata()
                    },
                shadowValidator =
                    ExtensionJarPreflightPipeline.ShadowValidator { _, _, _, _ ->
                        events += "shadow"
                        probeResult()
                    },
            )
        },
        postflightGuard = guard,
    )

    private class FakeTransport(
        private val events: MutableList<String>,
        private val root: Path,
    ) : ExtensionArtifactTransport {
        lateinit var lastPath: Path
            private set

        override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact {
            events += "download"
            assertEquals(JAR_URL, request.url)
            assertEquals(PACKAGE_NAME, request.packageName)
            lastPath = Files.writeString(root.resolve("coordinator-${System.nanoTime()}.jar"), "candidate")
            return StagedArtifact(
                path = lastPath,
                size = Files.size(lastPath),
                sha256 = CANDIDATE_SHA256,
                finalUrl = JAR_URL,
                kind = ArtifactKind.JAR,
            )
        }
    }

    private companion object {
        const val ENTRY_ID = 42L
        const val SNAPSHOT_TOKEN = "123e4567-e89b-12d3-a456-426614174000"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.fixture"
        const val SOURCE_ID = 1435103006121310663L
        const val SOURCE_NAME = "Fixture"
        const val STORE_URL = "https://repo.invalid/index.json"
        const val JAR_URL = "https://cdn.invalid/fixture.jar"
        const val SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val CANDIDATE_SHA256 = "dda18a0e21ae47c53b4309434cbc02ae8bf764fa83a6defbb719431242722aa7"

        fun snapshot() =
            ExtensionJarPreflightInputSnapshot(
                ledgerEntryID = ENTRY_ID,
                storeSnapshotToken = SNAPSHOT_TOKEN,
                expected = extensionInfo(),
                expectedSigningKey = SIGNING_KEY,
                occupiedSourceOwners = emptyMap(),
            )

        fun extensionInfo() =
            ExtensionInfo(
                storeIndexUrl = STORE_URL,
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
                sources =
                    listOf(
                        ExtensionSource(
                            id = SOURCE_ID,
                            name = SOURCE_NAME,
                            lang = "all",
                            homeUrl = "https://fixture.invalid",
                            message = null,
                            contentWarning = ContentWarning.SAFE,
                        ),
                    ),
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
