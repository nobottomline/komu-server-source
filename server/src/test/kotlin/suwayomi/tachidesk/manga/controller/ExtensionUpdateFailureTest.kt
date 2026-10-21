package suwayomi.tachidesk.manga.controller

import io.javalin.http.HttpStatus
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.extension.AmbiguousExtensionStoreOwnershipException
import suwayomi.tachidesk.manga.impl.extension.CustomPinnedExtensionUpdateException
import suwayomi.tachidesk.manga.impl.extension.ExtensionNotInstalledException
import suwayomi.tachidesk.manga.impl.extension.ExtensionUpdateStoreUnavailableException
import suwayomi.tachidesk.manga.impl.extension.NativeArtifactDownloadException
import suwayomi.tachidesk.manga.impl.extension.StaleExtensionStoreSnapshotException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionUpdateFailureTest {
    @Test
    fun `update conflicts have stable codes and never expose exception details`() {
        val failures =
            listOf(
                NoSuchElementException(SECRET) to "EXTENSION_NOT_FOUND",
                ExtensionNotInstalledException() to "EXTENSION_NOT_INSTALLED",
                CustomPinnedExtensionUpdateException() to "EXTENSION_CUSTOM_PINNED",
                ExtensionUpdateStoreUnavailableException() to "EXTENSION_STORE_UNAVAILABLE",
                AmbiguousExtensionStoreOwnershipException(SECRET, 2) to "EXTENSION_STORE_AMBIGUOUS",
                StaleExtensionStoreSnapshotException(SECRET) to "EXTENSION_STORE_CHANGED",
                IllegalStateException(SECRET) to "EXTENSION_UPDATE_FAILED",
            )
        for ((failure, code) in failures) {
            val response = ExtensionUpdateFailure.from(failure)
            assertEquals(code, response.body.code)
            assertFalse(response.body.message.contains(SECRET))
        }
        assertEquals(HttpStatus.NOT_FOUND, ExtensionUpdateFailure.from(failures.first().first).status)
        assertEquals(HttpStatus.CONFLICT, ExtensionUpdateFailure.from(CustomPinnedExtensionUpdateException()).status)
        assertTrue(ExtensionUpdateFailure.from(StaleExtensionStoreSnapshotException(SECRET)).body.retryable)
        assertFalse(ExtensionUpdateFailure.from(AmbiguousExtensionStoreOwnershipException(SECRET, 2)).body.retryable)
    }

    @Test
    fun `only classified native network failures advertise a retryable gateway error`() {
        val network = ExtensionUpdateFailure.from(NativeArtifactDownloadException("network-error", 0, SECRET))
        assertEquals(HttpStatus.BAD_GATEWAY, network.status)
        assertTrue(network.body.retryable)
        assertFalse(network.body.message.contains(SECRET))
        val invalidResult = ExtensionUpdateFailure.from(NativeArtifactDownloadException("json-error", 0, SECRET))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, invalidResult.status)
        assertFalse(invalidResult.body.retryable)
    }

    companion object {
        private const val SECRET = "private path URL SQL and untrusted metadata"
    }
}
