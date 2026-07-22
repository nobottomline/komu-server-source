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
            assertEquals(
                ExtensionActivationJournal.AuthenticatedStoreBinding(
                    packageName = PACKAGE_NAME,
                    ledgerEntryID = ENTRY_ID,
                    storeSnapshotToken = SNAPSHOT_TOKEN,
                    storeIndexUrl = STORE_URL,
                    storeSigningKey = SIGNING_KEY,
                    candidateSize = 9,
                    candidateSha256 = CANDIDATE_SHA256,
                    sourceIDs = listOf(SOURCE_ID),
                ),
                prepared.journalTrustBinding(),
            )
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

    @Test
    fun `successful journal handoff transfers cleanup ownership exactly once`() =
        runBlocking {
            val events = mutableListOf<String>()
            val transport = FakeTransport(events, temporaryDirectory)
            val prepared = coordinator(events, transport).prepare(PACKAGE_NAME, temporaryDirectory)
            val stagedPath = prepared.path
            val activePath = temporaryDirectory.resolve("active.jar")
            val trustBinding = prepared.journalTrustBinding()

            val journaled =
                prepared.transferToJournal(
                    databaseIdentity = DATABASE_IDENTITY,
                    activeArtifact = activePath,
                    previousArtifact = null,
                )

            assertEquals(ExtensionActivationJournal.State.PREPARED, journaled.entry.record.state)
            assertEquals(trustBinding, journaled.entry.record.storeTrustBinding)
            assertEquals(prepared.expected, journaled.expected)
            assertEquals(artifactMetadata(), journaled.metadata)
            assertEquals(listOf(SOURCE_ID), journaled.sources.map { it.id })
            assertEquals("all", journaled.extensionLanguage)
            assertEquals(JAR_URL, journaled.finalUrl)
            assertTrue(Files.exists(stagedPath))

            prepared.close()
            prepared.close()
            assertTrue(Files.exists(stagedPath), "preflight close must not delete journal-owned staging")
            assertThrows(IllegalStateException::class.java) { prepared.path }
            assertThrows(IllegalStateException::class.java) { prepared.assertIntegrity() }
            assertThrows(IllegalStateException::class.java) { prepared.journalTrustBinding() }
            assertThrows(IllegalStateException::class.java) {
                prepared.transferToJournal(DATABASE_IDENTITY, activePath, null)
            }

            journaled.journal.rollback(journaled.entry)
            assertFalse(Files.exists(stagedPath), "journal rollback is the sole cleanup owner after handoff")
            assertTrue(journaled.journal.loadAll().isEmpty())
        }

    @Test
    fun `failed journal handoff closes candidate and cannot be retried`() =
        runBlocking {
            val events = mutableListOf<String>()
            val transport = FakeTransport(events, temporaryDirectory)
            val prepared = coordinator(events, transport).prepare(PACKAGE_NAME, temporaryDirectory)
            val stagedPath = prepared.path
            val foreignRoot = temporaryDirectory.resolve("different-extension-root")

            assertThrows(IllegalArgumentException::class.java) {
                prepared.transferToJournal(
                    databaseIdentity = DATABASE_IDENTITY,
                    activeArtifact = foreignRoot.resolve("active.jar"),
                    previousArtifact = null,
                )
            }

            assertFalse(Files.exists(stagedPath))
            prepared.close()
            assertThrows(IllegalStateException::class.java) {
                prepared.transferToJournal(DATABASE_IDENTITY, temporaryDirectory.resolve("active.jar"), null)
            }
            assertTrue(ExtensionActivationJournal(foreignRoot).loadAll().isEmpty())
        }

    @Test
    fun `close before handoff deletes staging and permanently consumes ownership`() =
        runBlocking {
            val events = mutableListOf<String>()
            val transport = FakeTransport(events, temporaryDirectory)
            val prepared = coordinator(events, transport).prepare(PACKAGE_NAME, temporaryDirectory)
            val stagedPath = prepared.path

            prepared.close()
            prepared.close()

            assertFalse(Files.exists(stagedPath))
            assertThrows(IllegalStateException::class.java) { prepared.path }
            assertThrows(IllegalStateException::class.java) {
                prepared.transferToJournal(DATABASE_IDENTITY, temporaryDirectory.resolve("active.jar"), null)
            }
            assertFalse(Files.exists(stagedPath))
        }

    @Test
    fun `mutation immediately before journal handoff fails closed and deletes staging`() =
        runBlocking {
            val events = mutableListOf<String>()
            val transport = FakeTransport(events, temporaryDirectory)
            val prepared = coordinator(events, transport).prepare(PACKAGE_NAME, temporaryDirectory)
            val stagedPath = prepared.path
            Files.writeString(stagedPath, "tampered!")

            assertThrows(SecurityException::class.java) {
                prepared.transferToJournal(DATABASE_IDENTITY, temporaryDirectory.resolve("active.jar"), null)
            }

            assertFalse(Files.exists(stagedPath))
        }

    @Test
    fun `durable journal reference remains cleanup authority when handoff reports failure`() =
        runBlocking {
            val events = mutableListOf<String>()
            val transport = FakeTransport(events, temporaryDirectory)
            val prepared = coordinator(events, transport).prepare(PACKAGE_NAME, temporaryDirectory)
            val stagedPath = prepared.path
            val journal = ExtensionActivationJournal(temporaryDirectory)
            val existingEntry =
                journal.prepareAuthenticated(
                    packageName = PACKAGE_NAME,
                    databaseIdentity = DATABASE_IDENTITY,
                    stagedArtifact = stagedPath,
                    activeArtifact = temporaryDirectory.resolve("active.jar"),
                    previousArtifact = null,
                    storeTrustBinding = prepared.journalTrustBinding(),
                )

            assertThrows(PendingExtensionActivationException::class.java) {
                prepared.transferToJournal(
                    databaseIdentity = DATABASE_IDENTITY,
                    activeArtifact = temporaryDirectory.resolve("other-active.jar"),
                    previousArtifact = null,
                )
            }

            prepared.close()
            assertTrue(Files.exists(stagedPath), "durable journal reference must prevent preflight deletion")
            assertThrows(IllegalStateException::class.java) { prepared.path }
            journal.rollback(existingEntry)
            assertFalse(Files.exists(stagedPath))
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
        const val DATABASE_IDENTITY = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

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
