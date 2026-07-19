package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtensionJarArtifactInspectorTest {
    @Test
    fun `signed manifest is bound to exact store metadata`() {
        val metadata = ExtensionJarArtifactInspector.inspect(currentJar(), expectedInfo(), KEIYOUSHI_SIGNING_KEY)

        assertEquals(PACKAGE_NAME, metadata.packageName)
        assertEquals("$PACKAGE_NAME.ExtensionGenerated", metadata.classFQName)
        assertEquals(3, metadata.versionCode)
        assertEquals("1.4.3", metadata.versionName)
        assertEquals("1.4", metadata.extensionLib)
        assertEquals(ContentWarning.NSFW, metadata.contentWarning)
        assertEquals("AHottie", metadata.name)
        assertTrueAuthenticated(metadata)
    }

    @Test
    fun `signed artifact cannot satisfy a different store identity`() {
        val expected = expectedInfo()
        val mismatches =
            listOf(
                expected.copy(pkgName = "eu.kanade.tachiyomi.extension.all.replacement"),
                expected.copy(versionCode = 4),
                expected.copy(versionName = "1.4.4"),
                expected.copy(extensionLib = "1.5"),
                expected.copy(contentWarning = ContentWarning.MIXED),
                expected.copy(name = "Replacement"),
            )

        mismatches.forEach { mismatch ->
            assertFailsWith<SecurityException> {
                ExtensionJarArtifactInspector.inspect(currentJar(), mismatch, KEIYOUSHI_SIGNING_KEY)
            }
        }
    }

    private fun assertTrueAuthenticated(metadata: ExtensionJarArtifactInspector.Metadata) {
        assertEquals(KEIYOUSHI_SIGNING_KEY, metadata.signature.signerFingerprint)
        assertEquals(
            true,
            "${metadata.classFQName.replace('.', '/')}.class" in metadata.signature.verifiedEntryNames,
        )
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
                        id = 6289731484943315811,
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
        const val KEIYOUSHI_SIGNING_KEY =
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    }
}
