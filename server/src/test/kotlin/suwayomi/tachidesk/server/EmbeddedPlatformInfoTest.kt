package suwayomi.tachidesk.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.PlatformInfo
import suwayomi.tachidesk.server.util.OS
import suwayomi.tachidesk.server.util.Platform

class EmbeddedPlatformInfoTest {
    @Test
    fun `about platform details use JVM properties without desktop process execution on iOS`() {
        val previous = System.getProperty("komu.platform")
        try {
            System.setProperty("komu.platform", "ios")
            val darwin = OS.MACOS("Mac OS X")
            val payload = PlatformInfo(Platform.current.copy(os = darwin))
            assertEquals("iOS", payload.os.name)
            assertEquals(System.getProperty("os.version"), payload.os.version)
            assertNull(payload.os.build)
            assertEquals(System.getProperty("java.version"), payload.jvm.javaVersion)
        } finally {
            if (previous == null) System.clearProperty("komu.platform") else System.setProperty("komu.platform", previous)
        }
    }
}
