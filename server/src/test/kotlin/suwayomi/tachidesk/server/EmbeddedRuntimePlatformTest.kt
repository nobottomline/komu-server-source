package suwayomi.tachidesk.server

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddedRuntimePlatformTest {
    @Test
    fun `iOS disables desktop classpath scanning and CEF`() {
        assertTrue(EmbeddedRuntimePlatform.isIOS("ios"))
        assertFalse(EmbeddedRuntimePlatform.supportsClasspathScanning("ios"))
        assertFalse(EmbeddedRuntimePlatform.supportsCEF("ios"))
    }

    @Test
    fun `desktop and absent markers preserve upstream services`() {
        listOf(null, "", "desktop", "macos", "IOS").forEach { platform ->
            assertFalse(EmbeddedRuntimePlatform.isIOS(platform))
            assertTrue(EmbeddedRuntimePlatform.supportsClasspathScanning(platform))
            assertTrue(EmbeddedRuntimePlatform.supportsCEF(platform))
        }
    }
}
