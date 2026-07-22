package suwayomi.tachidesk.manga.impl.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtensionInstallArtifactPolicyTest {
    @Test
    fun `published record with both artifacts selects compatibility APK`() {
        val selection =
            ExtensionInstallArtifactPolicy.selectPublishedArtifact(
                apkUrl = APK_URL,
                jarUrl = JAR_URL,
                directJarEnabled = false,
            )

        assertEquals(ExtensionInstallArtifactPolicy.Kind.APK, selection.kind)
        assertEquals(APK_URL, selection.url)
    }

    @Test
    fun `published APK-only record remains installable`() {
        val selection =
            ExtensionInstallArtifactPolicy.selectPublishedArtifact(
                apkUrl = APK_URL,
                jarUrl = null,
                directJarEnabled = false,
            )

        assertEquals(ExtensionInstallArtifactPolicy.Kind.APK, selection.kind)
        assertEquals(APK_URL, selection.url)
    }

    @Test
    fun `published JAR-only record fails closed before download`() {
        assertFailsWith<DirectJarInstallationDisabledException> {
            ExtensionInstallArtifactPolicy.selectPublishedArtifact(
                apkUrl = null,
                jarUrl = JAR_URL,
                directJarEnabled = false,
            )
        }
    }

    @Test
    fun `exact opt-in selects published JAR ahead of compatibility APK`() {
        val selection =
            ExtensionInstallArtifactPolicy.selectPublishedArtifact(
                apkUrl = APK_URL,
                jarUrl = JAR_URL,
                directJarEnabled = true,
            )

        assertEquals(ExtensionInstallArtifactPolicy.Kind.DIRECT_JAR, selection.kind)
        assertEquals(JAR_URL, selection.url)
    }

    @Test
    fun `published mode is absent by default and rejects unsupported values`() {
        assertEquals(false, ExtensionInstallArtifactPolicy.publishedDirectJarEnabled(emptyMap()))
        assertEquals(
            true,
            ExtensionInstallArtifactPolicy.publishedDirectJarEnabled(
                mapOf(
                    ExtensionInstallArtifactPolicy.PUBLISHED_DIRECT_JAR_MODE to
                        ExtensionInstallArtifactPolicy.PUBLISHED_DIRECT_JAR_MODE_VALUE,
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            ExtensionInstallArtifactPolicy.publishedDirectJarEnabled(
                mapOf(ExtensionInstallArtifactPolicy.PUBLISHED_DIRECT_JAR_MODE to "true"),
            )
        }
    }

    @Test
    fun `external JAR execution is disabled by the same capability`() {
        assertFailsWith<DirectJarInstallationDisabledException> {
            ExtensionInstallArtifactPolicy.requireExternalJarExecution()
        }
    }

    @Test
    fun `blank artifact URLs are not considered usable`() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionInstallArtifactPolicy.selectPublishedArtifact(
                apkUrl = " ",
                jarUrl = "",
                directJarEnabled = false,
            )
        }
    }

    private companion object {
        const val APK_URL = "https://extensions.invalid/fixture.apk"
        const val JAR_URL = "https://extensions.invalid/fixture.jar"
    }
}
