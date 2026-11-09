package suwayomi.tachidesk.graphql

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.graphql.queries.InfoQuery
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Upstream #2458 drives these cases through its user-account GraphQL test
 * harness, which this line does not carry; the resolver behind `aboutWebUI`
 * is called directly instead. Komu always runs with WebUI serving disabled
 * and no local WebUI bundle.
 *
 * Touching `WebInterfaceManager` subscribes its WebUI update check to the
 * scheduler. The check is turned off first and stays off for the rest of the
 * run, so no scheduler job outlives this class (`HASchedulerTest` asserts an
 * empty scheduler in the same JVM).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AboutWebUIQueryTest : ApplicationTest() {
    private val infoQuery = InfoQuery()

    @BeforeAll
    fun disableWebUIUpdateChecks() {
        serverConfig.webUIUpdateCheckInterval.value = 0.0
    }

    private fun aboutWebUITag(): String = runBlocking { infoQuery.aboutWebUI().await().tag }

    @Test
    fun disabledWebUIWithoutLocalVersionDoesNotFailServerInfoQuery() {
        withWebUIVersion(enabled = false, revision = null) {
            assertEquals("r-1", aboutWebUITag())
        }
    }

    @Test
    fun enabledWebUIWithoutLocalVersionStillReportsAnError() {
        withWebUIVersion(enabled = true, revision = null) {
            assertFails { aboutWebUITag() }
        }
    }

    @Test
    fun localVersionIsPreservedWhetherWebUIIsEnabledOrDisabled() {
        for (enabled in listOf(false, true)) {
            withWebUIVersion(enabled = enabled, revision = "r1234\n") {
                assertEquals("r1234", aboutWebUITag())
            }
        }
    }

    private fun withWebUIVersion(
        enabled: Boolean,
        revision: String?,
        block: () -> Unit,
    ) {
        val originalEnabled = serverConfig.webUIEnabled.value
        val revisionFile = File(Injekt.get<ApplicationDirs>().webUIRoot, "revision")
        val originalRevision = revisionFile.takeIf { it.isFile }?.readBytes()

        try {
            if (revision == null) {
                check(!revisionFile.exists() || revisionFile.delete())
            } else {
                revisionFile.parentFile.mkdirs()
                revisionFile.writeText(revision)
            }

            serverConfig.webUIEnabled.value = enabled
            block()
        } finally {
            serverConfig.webUIEnabled.value = originalEnabled
            if (originalRevision != null) {
                revisionFile.writeBytes(originalRevision)
            } else {
                check(!revisionFile.exists() || revisionFile.delete())
            }
        }
    }
}
