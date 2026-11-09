package suwayomi.tachidesk.server.util

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebViewContractDiagnosticTest {
    private val jar = Files.createTempFile("contract", ".jar")
    private val env = mapOf(WebViewContractDiagnostic.JAR_KEY to jar.toString())

    @Test
    fun releaseBuildsNeverRunIt() {
        assertNull(WebViewContractDiagnostic.configuration(allowed = null, environment = env))
        assertNull(WebViewContractDiagnostic.configuration(allowed = "yes", environment = env))
    }

    @Test
    fun debugBuildsRunItOnlyWhenAStubIsGiven() {
        assertNull(WebViewContractDiagnostic.configuration(allowed = "allowed", environment = emptyMap()))
        val configuration = WebViewContractDiagnostic.configuration(allowed = "allowed", environment = env)
        assertEquals(jar, configuration?.jar)
        assertEquals("all", configuration?.scenarios)
        assertFailsWith<IllegalArgumentException> {
            WebViewContractDiagnostic.configuration(
                allowed = "allowed",
                environment = mapOf(WebViewContractDiagnostic.JAR_KEY to jar.resolveSibling("missing.jar").toString()),
            )
        }
    }
}
