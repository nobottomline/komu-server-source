package suwayomi.tachidesk.manga.impl.extension

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeExtensionArtifactTransportTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `native file is adopted only after independent digest verification`() =
        runBlocking {
            val payload = "signed-native-artifact".toByteArray()
            lateinit var nativeFile: Path
            val bridge =
                FakeNativeBridge(download = { operationID ->
                    nativeFile = nativeFile(payload, operationID)
                    success(nativeFile, payload)
                })

            val staged = transport(bridge).download(request())

            assertFalse(Files.exists(nativeFile))
            assertEquals(payload.toList(), Files.readAllBytes(staged.path).toList())
            assertEquals(payload.size.toLong(), staged.size)
            assertEquals(sha256(payload), staged.sha256)
            assertEquals(HTTPS_URL, staged.finalUrl)
            staged.assertIntegrity(1024)
            staged.close()
            assertFalse(Files.exists(staged.path))
        }

    @Test
    fun `ABI mismatch fails before preparing native operation`() {
        val bridge = FakeNativeBridge(abiVersion = 1, download = { error("download must not run") })

        assertFailsWith<IllegalStateException> { runBlocking { transport(bridge).download(request()) } }

        assertEquals(0, bridge.prepareCount)
        assertEquals(0, bridge.downloadCount)
    }

    @Test
    fun `prepare rejection prevents download`() {
        val bridge = FakeNativeBridge(prepareResult = false, download = { error("download must not run") })

        assertFailsWith<IllegalStateException> { runBlocking { transport(bridge).download(request()) } }

        assertEquals(1, bridge.prepareCount)
        assertEquals(0, bridge.downloadCount)
    }

    @Test
    fun `native failure envelope is preserved as typed error`() {
        val bridge =
            FakeNativeBridge(
                download = {
                    encode(
                        NativeArtifactDownloadResult(
                            abiVersion = 2,
                            ok = false,
                            status = 503,
                            errorCode = "network-error",
                            message = "offline",
                        ),
                    )
                },
            )

        val failure = assertFailsWith<NativeArtifactDownloadException> { runBlocking { transport(bridge).download(request()) } }

        assertEquals("network-error", failure.errorCode)
        assertEquals(503, failure.status)
    }

    @Test
    fun `native digest mismatch deletes only operation-owned files`() {
        val payload = "tampered".toByteArray()
        lateinit var nativeFile: Path
        val bridge =
            FakeNativeBridge(download = { operationID ->
                nativeFile = nativeFile(payload, operationID)
                success(nativeFile, payload, digest = "0".repeat(64))
            })

        assertFailsWith<SecurityException> { runBlocking { transport(bridge).download(request()) } }

        assertFalse(Files.exists(nativeFile))
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `forged same-name temporary root is rejected without deleting unrelated file`() {
        val forgedRoot = temporaryDirectory.resolve("other").resolve(NATIVE_ROOT_NAME)
        Files.createDirectories(forgedRoot)
        lateinit var unrelated: Path
        val payload = "keep".toByteArray()
        val bridge =
            FakeNativeBridge(download = { operationID ->
                unrelated = forgedRoot.resolve("artifact-$operationID.download")
                Files.write(unrelated, payload)
                success(unrelated, payload)
            })

        assertFailsWith<IllegalArgumentException> { runBlocking { transport(bridge).download(request()) } }

        assertTrue(Files.exists(unrelated))
        assertEquals("keep", Files.readString(unrelated))
    }

    @Test
    fun `malformed success digest is rejected and owned native file is cleaned`() {
        val payload = "artifact".toByteArray()
        lateinit var nativeFile: Path
        val bridge =
            FakeNativeBridge(download = { operationID ->
                nativeFile = nativeFile(payload, operationID)
                success(nativeFile, payload, digest = "not-a-digest")
            })

        assertFailsWith<IllegalArgumentException> { runBlocking { transport(bridge).download(request()) } }

        assertFalse(Files.exists(nativeFile))
    }

    @Test
    fun `coroutine cancellation reaches native task and cleans late owned file`() =
        runBlocking {
            val payload = "late-artifact".toByteArray()
            lateinit var nativeFile: Path
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val bridge =
                FakeNativeBridge(
                    download = { operationID ->
                        nativeFile = nativeFile(payload, operationID)
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        success(nativeFile, payload)
                    },
                    onCancel = { cancelled.countDown() },
                )
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "native-artifact-test").apply { isDaemon = true }
                }

            val job = launch(start = CoroutineStart.UNDISPATCHED) { transport(bridge, executor).download(request()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            release.countDown()

            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(nativeFile))
        }

    private fun request() =
        ExtensionArtifactTransport.Request(
            url = HTTPS_URL,
            stagingDirectory = stagingDirectory(),
            packageName = PACKAGE_NAME,
            kind = ArtifactKind.JAR,
        )

    private fun transport(
        bridge: NativeArtifactBridge,
        executor: Executor = DIRECT_EXECUTOR,
    ) = NativeExtensionArtifactTransport(
        bridge = bridge,
        executor = executor,
        maxArtifactBytes = 1024,
        nativeTemporaryRoot = nativeRoot(),
    )

    private fun nativeFile(
        payload: ByteArray,
        operationID: String,
    ): Path = nativeRoot().resolve("artifact-$operationID.download").also { Files.write(it, payload) }

    private fun success(
        path: Path,
        payload: ByteArray,
        digest: String = sha256(payload),
    ): ByteArray =
        encode(
            NativeArtifactDownloadResult(
                abiVersion = 2,
                ok = true,
                status = 200,
                finalUrl = HTTPS_URL,
                path = path.toAbsolutePath().toString(),
                size = payload.size.toLong(),
                sha256 = digest,
            ),
        )

    private fun encode(result: NativeArtifactDownloadResult): ByteArray = Json.encodeToString(result).toByteArray(StandardCharsets.UTF_8)

    private fun nativeRoot(): Path = temporaryDirectory.resolve(NATIVE_ROOT_NAME).also { Files.createDirectories(it) }

    private fun stagingDirectory(): Path = temporaryDirectory.resolve("staging").also { Files.createDirectories(it) }

    private fun stagingFiles(): List<Path> = Files.list(stagingDirectory()).use { it.toList() }

    private fun sha256(payload: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private class FakeNativeBridge(
        private val abiVersion: Int = 2,
        private val prepareResult: Boolean = true,
        private val download: (String) -> ByteArray,
        private val onCancel: () -> Unit = {},
    ) : NativeArtifactBridge {
        var prepareCount = 0
            private set
        var downloadCount = 0
            private set

        override fun abiVersion(): Int = abiVersion

        override fun prepare(operationID: ByteArray): Boolean {
            prepareCount += 1
            return prepareResult
        }

        override fun downloadToTemporaryFile(
            operationID: ByteArray,
            url: ByteArray,
            maximumBytes: Long,
        ): ByteArray {
            downloadCount += 1
            return download(operationID.toString(StandardCharsets.UTF_8))
        }

        override fun cancel(operationID: ByteArray) = onCancel()
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor { it.run() }
        const val HTTPS_URL = "https://cdn.invalid/extension.jar"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.fixture"
        const val NATIVE_ROOT_NAME = "komu-extension-artifacts-v1"
    }
}
