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
            )
        }
    }

    private companion object {
        const val APK_URL = "https://extensions.invalid/fixture.apk"
        const val JAR_URL = "https://extensions.invalid/fixture.jar"
    }
}
