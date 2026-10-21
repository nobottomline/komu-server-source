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

    @Test
    fun `API 1_6 fixed generated entry point is supported without accepting neighboring namespaces`() {
        val packageName = PinnedKeiyoushiRealArtifact.PACKAGE_NAME
        assertEquals(
            "keiyoushi.source.Generated",
            ExtensionJarArtifactInspector.resolveSourceClassName("keiyoushi.source.Generated", packageName, "1.6"),
        )
        listOf(
            "keiyoushi.source.Other",
            "keiyoushi.source.GeneratedExtra",
            "keiyoushi.source.Generated.Nested",
            "keiyoushi.source.Generated\$Nested",
            "other.source.Generated",
        ).forEach { sourceClass ->
            assertFailsWith<SecurityException> {
                ExtensionJarArtifactInspector.resolveSourceClassName(sourceClass, packageName, "1.6")
            }
        }
        assertFailsWith<SecurityException> {
            ExtensionJarArtifactInspector.resolveSourceClassName("keiyoushi.source.Generated", packageName, "1.4")
        }
    }

    @Test
    fun `legacy package-local source class resolution is preserved`() {
        val packageName = PinnedKeiyoushiRealArtifact.PACKAGE_NAME
        listOf(".ExtensionGenerated", "$packageName.ExtensionGenerated").forEach { sourceClass ->
            assertEquals(
                "$packageName.ExtensionGenerated",
                ExtensionJarArtifactInspector.resolveSourceClassName(sourceClass, packageName, "1.4"),
            )
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
