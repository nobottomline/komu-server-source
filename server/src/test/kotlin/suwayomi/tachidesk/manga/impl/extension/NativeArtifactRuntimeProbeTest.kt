package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeArtifactRuntimeProbeTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `matching ABI is available`() {
        val bridge = ProbeBridge(abiVersionProvider = { NATIVE_ARTIFACT_ABI_VERSION })
        val root = expectedRoot()

        val status = NativeArtifactRuntimeProbe.probe(bridge, root)

        assertTrue(status.available)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION, status.expectedAbiVersion)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION, status.actualAbiVersion)
        assertEquals(true, status.temporaryRootVerified)
        assertNull(status.failureType)
        assertEquals(1, bridge.abiVersionCallCount)
        assertEquals(1, bridge.temporaryRootCallCount)
        assertEquals(root.toString(), bridge.expectedRootBytes?.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `mismatched ABI is unavailable without a linkage failure`() {
        val bridge = ProbeBridge(abiVersionProvider = { NATIVE_ARTIFACT_ABI_VERSION - 1 })

        val status = NativeArtifactRuntimeProbe.probe(bridge, expectedRoot())

        assertFalse(status.available)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION - 1, status.actualAbiVersion)
        assertNull(status.temporaryRootVerified)
        assertNull(status.failureType)
        assertEquals(1, bridge.abiVersionCallCount)
        assertEquals(0, bridge.temporaryRootCallCount)
    }

    @Test
    fun `linkage failure is contained and classified`() {
        val bridge =
            ProbeBridge(abiVersionProvider = { throw UnsatisfiedLinkError("diagnostic detail must not escape") })

        val status = NativeArtifactRuntimeProbe.probe(bridge, expectedRoot())

        assertFalse(status.available)
        assertNull(status.actualAbiVersion)
        assertNull(status.temporaryRootVerified)
        assertEquals(UnsatisfiedLinkError::class.java.name, status.failureType)
        assertEquals(1, bridge.abiVersionCallCount)
        assertEquals(0, bridge.temporaryRootCallCount)
    }

    @Test
    fun `temporary root identity mismatch is unavailable without exposing paths`() {
        val bridge =
            ProbeBridge(
                abiVersionProvider = { NATIVE_ARTIFACT_ABI_VERSION },
                temporaryRootProvider = { false },
            )

        val status = NativeArtifactRuntimeProbe.probe(bridge, expectedRoot())

        assertFalse(status.available)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION, status.actualAbiVersion)
        assertEquals(false, status.temporaryRootVerified)
        assertNull(status.failureType)
        assertEquals(1, bridge.temporaryRootCallCount)
    }

    @Test
    fun `temporary root linkage failure is contained and classified`() {
        val bridge =
            ProbeBridge(
                abiVersionProvider = { NATIVE_ARTIFACT_ABI_VERSION },
                temporaryRootProvider = { throw UnsatisfiedLinkError("private container path") },
            )

        val status = NativeArtifactRuntimeProbe.probe(bridge, expectedRoot())

        assertFalse(status.available)
        assertEquals(NATIVE_ARTIFACT_ABI_VERSION, status.actualAbiVersion)
        assertNull(status.temporaryRootVerified)
        assertEquals(UnsatisfiedLinkError::class.java.name, status.failureType)
        assertEquals(1, bridge.temporaryRootCallCount)
    }

    private class ProbeBridge(
        private val abiVersionProvider: () -> Int,
        private val temporaryRootProvider: (ByteArray) -> Boolean = { true },
    ) : NativeArtifactBridge {
        var abiVersionCallCount = 0
            private set
        var temporaryRootCallCount = 0
            private set
        var expectedRootBytes: ByteArray? = null
            private set

        override fun abiVersion(): Int {
            abiVersionCallCount += 1
            return abiVersionProvider()
        }

        override fun temporaryRootMatches(expectedPath: ByteArray): Boolean {
            temporaryRootCallCount += 1
            expectedRootBytes = expectedPath.copyOf()
            return temporaryRootProvider(expectedPath)
        }

        override fun prepare(operationID: ByteArray): Boolean = error("prepare must not run")

        override fun downloadToTemporaryFile(
            operationID: ByteArray,
            url: ByteArray,
            maximumBytes: Long,
        ): ByteArray = error("download must not run")

        override fun cancel(operationID: ByteArray) = error("cancel must not run")
    }

    private fun expectedRoot(): Path = temporaryDirectory.resolve(NATIVE_TEMP_DIRECTORY).toAbsolutePath().normalize()
}
