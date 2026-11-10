package app.komu.webview

import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebViewContractQualificationTest {
    @Test
    fun theStubMustBeAFileAndScenariosDefaultToAll() {
        val jar = Files.createTempFile("contract", ".jar")
        assertEquals(jar to "all", WebViewContractQualification.validated(jar.toString(), ""))
        assertEquals(jar to "basic", WebViewContractQualification.validated(jar.toString(), "basic"))
        assertFailsWith<IllegalArgumentException> {
            WebViewContractQualification.validated(jar.resolveSibling("missing-${System.nanoTime()}.jar").toString(), "all")
        }
    }

    /** ADR-0087 Review 1: the diagnostic lives outside the main source set, so the server JAR cannot carry it. */
    @Test
    fun theDiagnosticIsNotInTheMainSourceSet() {
        val location =
            WebViewContractQualification::class.java.protectionDomain.codeSource.location
                .toURI()
                .path
        assertTrue("qualification" in location, "loaded from $location")
        val main = suwayomi.tachidesk.server.ServerConfig::class.java.protectionDomain.codeSource.location.toURI().path
        if (main.endsWith(".jar")) {
            JarFile(main).use { assertTrue(it.getEntry("app/komu/webview/WebViewContractQualification.class") == null) }
        }
    }
}
