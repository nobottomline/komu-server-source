package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NativeArtifactTransportDiagnosticTest {
    @Test
    fun `diagnostic is absent unless the exact opt-in is present`() {
        assertNull(NativeArtifactTransportDiagnostic.configurationFromEnvironment(emptyMap()))
    }

    @Test
    fun `valid pinned configuration is parsed without defaults`() {
        val configuration = NativeArtifactTransportDiagnostic.configurationFromEnvironment(validEnvironment())

        requireNotNull(configuration)
        assertEquals(URL, configuration.url)
        assertEquals(47_773, configuration.expectedSize)
        assertEquals(SHA256, configuration.expectedSha256)
    }

    @Test
    fun `unsupported mode and partial inputs fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            NativeArtifactTransportDiagnostic.configurationFromEnvironment(
                validEnvironment() + (ENABLE_KEY to "true"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeArtifactTransportDiagnostic.configurationFromEnvironment(validEnvironment() - SHA256_KEY)
        }
    }

    @Test
    fun `unpinned or non HTTPS diagnostics fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            NativeArtifactTransportDiagnostic.configurationFromEnvironment(
                validEnvironment() + (URL_KEY to "http://example.invalid/artifact.jar"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeArtifactTransportDiagnostic.configurationFromEnvironment(
                validEnvironment() + (SHA256_KEY to "not-a-digest"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeArtifactTransportDiagnostic.configurationFromEnvironment(
                validEnvironment() + (SIZE_KEY to "0"),
            )
        }
    }

    private fun validEnvironment() =
        mapOf(
            ENABLE_KEY to "download-cancel-v1",
            URL_KEY to URL,
            SHA256_KEY to SHA256,
            SIZE_KEY to "47773",
        )

    private companion object {
        const val ENABLE_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC"
        const val URL_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC_URL"
        const val SHA256_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC_SHA256"
        const val SIZE_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC_SIZE"
        const val URL = "https://cdn.invalid/artifact.jar"
        const val SHA256 = "2b36f1437e76ad2ff98beaaa07994f3fae853e3b2582f3cde14a1337e1d1dc34"
    }
}
