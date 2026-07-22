package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtensionJarArtifactInspectorTest {
    @Test
    fun `signed manifest is bound to exact store metadata`() {
        val fixture = PinnedKeiyoushiRealArtifact.load()
        val metadata = ExtensionJarArtifactInspector.inspect(fixture.jar, fixture.expected, PinnedKeiyoushiRealArtifact.SIGNING_KEY)

        assertEquals(PinnedKeiyoushiRealArtifact.PACKAGE_NAME, metadata.packageName)
        assertEquals("${PinnedKeiyoushiRealArtifact.PACKAGE_NAME}.ExtensionGenerated", metadata.classFQName)
        assertEquals(3, metadata.versionCode)
        assertEquals("1.4.3", metadata.versionName)
        assertEquals("1.4", metadata.extensionLib)
        assertEquals(fixture.expected.contentWarning, metadata.contentWarning)
        assertEquals("AHottie", metadata.name)
        assertTrueAuthenticated(metadata)
    }

    @Test
    fun `signed artifact cannot satisfy a different store identity`() {
        val fixture = PinnedKeiyoushiRealArtifact.load()
        val expected = fixture.expected
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
                ExtensionJarArtifactInspector.inspect(fixture.jar, mismatch, PinnedKeiyoushiRealArtifact.SIGNING_KEY)
            }
        }
    }

    private fun assertTrueAuthenticated(metadata: ExtensionJarArtifactInspector.Metadata) {
        assertEquals(PinnedKeiyoushiRealArtifact.SIGNING_KEY, metadata.signature.signerFingerprint)
        assertEquals(
            true,
            "${metadata.classFQName.replace('.', '/')}.class" in metadata.signature.verifiedEntryNames,
        )
    }
}
