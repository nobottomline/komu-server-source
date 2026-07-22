package suwayomi.tachidesk.manga.impl.extension

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.test.ApplicationTest
import java.nio.file.Files
import java.nio.file.Path

class ExtensionJarPreflightRealArtifactTest : ApplicationTest() {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `signed Keiyoushi jar passes complete pre-activation pipeline without publishing state`() =
        runBlocking {
            val fixture = PinnedKeiyoushiRealArtifact.load()
            val publishedJar = fixture.jar
            val loaderKeysBefore = PackageTools.jarLoaderMap.keys.toSet()
            val rowCountsBefore = transaction { SourceTable.selectAll().count() to ExtensionTable.selectAll().count() }
            val candidate =
                ExtensionJarPreflightPipeline(CopyingTransport(publishedJar)).prepare(
                    expected = fixture.expected,
                    expectedSigningKey = PinnedKeiyoushiRealArtifact.SIGNING_KEY,
                    stagingDirectory = temporaryDirectory,
                    occupiedSourceOwners = emptyMap(),
                )

            assertEquals(PinnedKeiyoushiRealArtifact.PACKAGE_NAME, candidate.metadata.packageName)
            assertEquals(PinnedKeiyoushiRealArtifact.SIGNING_KEY, candidate.metadata.signature.signerFingerprint)
            assertEquals(listOf(PinnedKeiyoushiRealArtifact.SOURCE_ID), candidate.sources.map { it.id })
            assertEquals("AHottie", candidate.sources.single().name)
            assertEquals("all", candidate.extensionLanguage)
            assertTrue(Files.isRegularFile(candidate.path))
            assertTrue(Files.isRegularFile(publishedJar))
            assertEquals(loaderKeysBefore, PackageTools.jarLoaderMap.keys.toSet())
            assertEquals(rowCountsBefore, transaction { SourceTable.selectAll().count() to ExtensionTable.selectAll().count() })
            candidate.assertIntegrity()
            candidate.close()
            assertFalse(Files.exists(candidate.path))
            assertTrue(Files.isRegularFile(publishedJar))
        }

    private class CopyingTransport(
        private val publishedJar: Path,
    ) : ExtensionArtifactTransport {
        override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact {
            val staged = Files.copy(publishedJar, request.stagingDirectory.resolve("real-${System.nanoTime()}.jar"))
            return StagedArtifact(
                path = staged,
                size = Files.size(staged),
                sha256 = StagedArtifact.sha256(staged, StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES),
                finalUrl = request.url,
                kind = request.kind,
            )
        }
    }
}
