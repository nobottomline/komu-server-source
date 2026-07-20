package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ExtensionArtifactTransportSelectorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `iOS selects an attested native transport without constructing desktop transport`() {
        val native = nativeTransport(available = true)
        var nativeCreations = 0
        var desktopCreations = 0
        val selector =
            ExtensionArtifactTransportSelector(
                platformProvider = { "ios" },
                nativeTransportFactory = { native.also { nativeCreations += 1 } },
                desktopTransportFactory = { FakeTransport().also { desktopCreations += 1 } },
            )

        assertSame(native, selector.select())
        assertEquals(1, nativeCreations)
        assertEquals(0, desktopCreations)
    }

    @Test
    fun `iOS fails closed when native attestation fails and never falls back to desktop`() {
        var desktopCreations = 0
        val selector =
            ExtensionArtifactTransportSelector(
                platformProvider = { "ios" },
                nativeTransportFactory = { nativeTransport(available = false) },
                desktopTransportFactory = { FakeTransport().also { desktopCreations += 1 } },
            )

        assertFailsWith<IllegalStateException> { selector.select() }
        assertEquals(0, desktopCreations)
    }

    @Test
    fun `desktop constructs only desktop transport and never probes JNI`() {
        val desktop = FakeTransport()
        var nativeCreations = 0
        val selector =
            ExtensionArtifactTransportSelector(
                platformProvider = { null },
                nativeTransportFactory = {
                    nativeCreations += 1
                    error("native transport must not be constructed")
                },
                desktopTransportFactory = { desktop },
            )

        assertSame(desktop, selector.select())
        assertEquals(0, nativeCreations)
    }

    @Test
    fun `non-canonical platform spelling is not treated as iOS`() {
        val desktop = FakeTransport()
        val selector =
            ExtensionArtifactTransportSelector(
                platformProvider = { "IOS" },
                nativeTransportFactory = { error("native transport must not be constructed") },
                desktopTransportFactory = { desktop },
            )

        assertSame(desktop, selector.select())
    }

    private fun nativeTransport(available: Boolean) =
        NativeExtensionArtifactTransport(
            bridge = ProbeBridge(available),
            nativeTemporaryRoot = temporaryDirectory,
        )

    private class ProbeBridge(
        private val available: Boolean,
    ) : NativeArtifactBridge {
        override fun abiVersion(): Int = NATIVE_ARTIFACT_ABI_VERSION

        override fun temporaryRootMatches(expectedPath: ByteArray): Boolean = available

        override fun prepare(operationID: ByteArray): Boolean = error("prepare must not run during selection")

        override fun downloadToTemporaryFile(
            operationID: ByteArray,
            url: ByteArray,
            maximumBytes: Long,
        ): ByteArray = error("download must not run during selection")

        override fun cancel(operationID: ByteArray) = error("cancel must not run during selection")
    }

    private class FakeTransport : ExtensionArtifactTransport {
        override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact = error("selector tests never download")
    }
}
