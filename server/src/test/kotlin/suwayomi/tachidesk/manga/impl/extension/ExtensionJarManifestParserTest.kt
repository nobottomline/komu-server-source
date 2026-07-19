package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtensionJarManifestParserTest {
    @Test
    fun `minimal manifest is decoded`() {
        val manifest = ExtensionJarManifestParser.parse(validManifest().encodeToByteArray())

        assertEquals(PACKAGE_NAME, manifest.packageName)
        assertEquals(3, manifest.versionCode)
        assertEquals("1.4.3", manifest.versionName)
        assertEquals("tachiyomi.extension", manifest.usesFeatures.single().name)
        assertEquals(
            ".ExtensionGenerated",
            manifest.application
                ?.metaData
                ?.single()
                ?.value,
        )
    }

    @Test
    fun `doctype and entity declarations are rejected`() {
        val manifest =
            validManifest().replace(
                "<manifest",
                "<!DOCTYPE manifest [<!ENTITY injected SYSTEM \"file:///etc/passwd\">]><manifest",
            )

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarManifestParser.parse(manifest.encodeToByteArray())
        }
    }

    @Test
    fun `malformed UTF-8 is rejected`() {
        assertFailsWith<Exception> {
            ExtensionJarManifestParser.parse(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun `duplicate metadata is rejected`() {
        val duplicate =
            "<meta-data android:name=\"tachiyomi.extension.class\" android:value=\".Other\"/>"
        val manifest = validManifest().replace("</application>", "$duplicate</application>")

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarManifestParser.parse(manifest.encodeToByteArray())
        }
    }

    @Test
    fun `manifest larger than parser ceiling is rejected`() {
        val oversized = ByteArray(ExtensionJarManifestParser.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarManifestParser.parse(oversized)
        }
    }

    private fun validManifest() =
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="$PACKAGE_NAME" android:versionCode="3" android:versionName="1.4.3">
            <uses-feature android:name="tachiyomi.extension"/>
            <application android:label="Tachiyomi: Fixture">
                <meta-data android:name="tachiyomi.extension.class" android:value=".ExtensionGenerated"/>
            </application>
        </manifest>
        """.trimIndent()

    private companion object {
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.fixture"
    }
}
