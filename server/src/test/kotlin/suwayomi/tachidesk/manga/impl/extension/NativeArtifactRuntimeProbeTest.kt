package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeArtifactRuntimeProbeTest {
    @Test
    fun `matching ABI is available`() {
        val bridge = ProbeBridge { NATIVE_ARTIFACT_ABI_VERSION }

        val status = NativeArtifactRuntimeProbe.probe(bridge)

        assertTrue(status.available)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION, status.expectedAbiVersion)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION, status.actualAbiVersion)
        assertNull(status.failureType)
        assertEquals(1, bridge.abiVersionCallCount)
    }

    @Test
    fun `mismatched ABI is unavailable without a linkage failure`() {
        val bridge = ProbeBridge { NATIVE_ARTIFACT_ABI_VERSION - 1 }

        val status = NativeArtifactRuntimeProbe.probe(bridge)

        assertFalse(status.available)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION - 1, status.actualAbiVersion)
        assertNull(status.failureType)
        assertEquals(1, bridge.abiVersionCallCount)
    }

    @Test
    fun `linkage failure is contained and classified`() {
        val bridge = ProbeBridge { throw UnsatisfiedLinkError("diagnostic detail must not escape") }

        val status = NativeArtifactRuntimeProbe.probe(bridge)

        assertFalse(status.available)
        assertNull(status.actualAbiVersion)
        assertEquals(UnsatisfiedLinkError::class.java.name, status.failureType)
        assertEquals(1, bridge.abiVersionCallCount)
    }

    private class ProbeBridge(
        private val abiVersionProvider: () -> Int,
    ) : NativeArtifactBridge {
        var abiVersionCallCount = 0
            private set

        override fun abiVersion(): Int {
            abiVersionCallCount += 1
            return abiVersionProvider()
        }

        override fun prepare(operationID: ByteArray): Boolean = error("prepare must not run")

        override fun downloadToTemporaryFile(
            operationID: ByteArray,
            url: ByteArray,
            maximumBytes: Long,
        ): ByteArray = error("download must not run")

        override fun cancel(operationID: ByteArray) = error("cancel must not run")
    }
}
