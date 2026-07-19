package suwayomi.tachidesk.manga.impl.extension

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
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
            val publishedJar = currentJar()
            val loaderKeysBefore = PackageTools.jarLoaderMap.keys.toSet()
            val rowCountsBefore = transaction { SourceTable.selectAll().count() to ExtensionTable.selectAll().count() }
            val candidate =
                ExtensionJarPreflightPipeline(CopyingTransport(publishedJar)).prepare(
                    expected = expectedInfo(),
                    expectedSigningKey = KEIYOUSHI_SIGNING_KEY,
                    stagingDirectory = temporaryDirectory,
                    occupiedSourceOwners = emptyMap(),
                )

            assertEquals(PACKAGE_NAME, candidate.metadata.packageName)
            assertEquals(KEIYOUSHI_SIGNING_KEY, candidate.metadata.signature.signerFingerprint)
            assertEquals(listOf(SOURCE_ID), candidate.sources.map { it.id })
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

    private fun currentJar(): Path {
        val repoDirectory =
            System.getProperty("komu.keiyoushiRepoDir")
                ?: System.getenv("KOMU_KEIYOUSHI_REPO_DIR")
        assumeTrue(!repoDirectory.isNullOrBlank(), "Keiyoushi repository path is not configured")
        return Path
            .of(repoDirectory)
            .resolve("jar/tachiyomi-all.ahottie-v1.4.3.jar")
            .also { assumeTrue(Files.isRegularFile(it), "Signed AHottie fixture is unavailable") }
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

    private fun expectedInfo() =
        ExtensionInfo(
            storeIndexUrl = "https://github.com/keiyoushi/extensions/raw/repo/index.pb",
            name = "AHottie",
            pkgName = PACKAGE_NAME,
            apkUrl = "https://cdn.invalid/tachiyomi-all.ahottie-v1.4.3.apk",
            jarUrl = "https://cdn.invalid/tachiyomi-all.ahottie-v1.4.3.jar",
            iconUrl = "https://cdn.invalid/ahottie.png",
            extensionLib = "1.4",
            versionCode = 3,
            versionName = "1.4.3",
            lang = "all",
            contentWarning = ContentWarning.NSFW,
            sources =
                listOf(
                    ExtensionSource(
                        id = SOURCE_ID,
                        name = "AHottie",
                        lang = "all",
                        homeUrl = "https://ahottie.top",
                        message = null,
                        contentWarning = ContentWarning.NSFW,
                    ),
                ),
        )

    private companion object {
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.ahottie"
        const val SOURCE_ID = 6289731484943315811L
        const val KEIYOUSHI_SIGNING_KEY =
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    }
}
