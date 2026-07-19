package suwayomi.tachidesk.manga.impl.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopExtensionArtifactTransportTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `valid response is staged with digest integrity and owned cleanup`() =
        runBlocking {
            val payload = "signed-jar-placeholder".toByteArray()
            val transport = transport(respondWith(payload.toResponseBody()))

            val staged = transport.download(request())

            assertEquals(payload.toList(), Files.readAllBytes(staged.path).toList())
            assertEquals(payload.size.toLong(), staged.size)
            assertEquals("45adaee3638087f178ecfb849729e78c829d44966c63f9c4bbfeefefa89263d0", staged.sha256)
            assertEquals(HTTPS_URL, staged.finalUrl)
            staged.assertIntegrity(1024)

            val tamperedPayload = payload.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
            Files.write(staged.path, tamperedPayload)
            assertFailsWith<SecurityException> { staged.assertIntegrity(1024) }
            staged.close()
            staged.close()
            assertFalse(Files.exists(staged.path))
        }

    @Test
    fun `relative HTTPS redirect is validated before second request`() =
        runBlocking {
            val calls = AtomicInteger()
            val client =
                dedicatedTestClient { request ->
                    if (calls.getAndIncrement() == 0) {
                        response(request, 302, "redirect".toResponseBody(), location = "/final.jar")
                    } else {
                        response(request, 200, "payload".toResponseBody())
                    }
                }

            val staged = transport(client).download(request())

            assertEquals("https://cdn.invalid/final.jar", staged.finalUrl)
            assertEquals(2, calls.get())
            staged.close()
        }

    @Test
    fun `redirect to insecure URL is rejected before another request`() {
        val calls = AtomicInteger()
        val client =
            dedicatedTestClient { request ->
                calls.incrementAndGet()
                response(request, 302, "redirect".toResponseBody(), location = "http://cdn.invalid/final.jar")
            }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport(client).download(request()) }
        }
        assertEquals(1, calls.get())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `unknown length response is stopped at streaming limit`() {
        val transport = transport(respondWith(UnknownLengthBody(ByteArray(9))), maximumBytes = 8)

        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport.download(request()) }
        }
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `declared oversized response is rejected before writing`() {
        val transport = transport(respondWith(ByteArray(9).toResponseBody()), maximumBytes = 8)

        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport.download(request()) }
        }
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `initial URL credentials and package traversal fail before network`() {
        val calls = AtomicInteger()
        val client = dedicatedTestClient { request -> calls.incrementAndGet().let { response(request, 200, "payload".toResponseBody()) } }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport(client).download(request(url = "https://user:secret@cdn.invalid/a.jar")) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport(client).download(request(packageName = "../escape")) }
        }
        assertEquals(0, calls.get())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `non-identity content encoding is rejected`() {
        val client =
            dedicatedTestClient { request ->
                response(request, 200, "compressed".toResponseBody(), contentEncoding = "gzip")
            }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport(client).download(request()) }
        }
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `coroutine cancellation cancels call and removes operation file`() =
        runBlocking {
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val client =
                OkHttpClient
                    .Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .addInterceptor { chain ->
                        started.countDown()
                        while (!chain.call().isCanceled()) Thread.sleep(5)
                        cancelled.countDown()
                        throw IOException("cancelled")
                    }.build()
            val job = launch(Dispatchers.IO) { transport(client).download(request()) }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(stagingFiles().isEmpty())
        }

    @Test
    fun `redirect loop is rejected without exhausting network policy`() {
        val calls = AtomicInteger()
        val client =
            dedicatedTestClient { request ->
                calls.incrementAndGet()
                response(request, 302, "redirect".toResponseBody(), location = HTTPS_URL)
            }

        assertFailsWith<IllegalStateException> {
            runBlocking { transport(client).download(request()) }
        }
        assertEquals(1, calls.get())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `ambiguous redirect location is rejected`() {
        val client =
            dedicatedTestClient { request ->
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("fixture")
                    .body("redirect".toResponseBody())
                    .addHeader("Location", "/first.jar")
                    .addHeader("Location", "/second.jar")
                    .build()
            }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport(client).download(request()) }
        }
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `symlink staging directory is rejected before network`() {
        val realDirectory = temporaryDirectory.resolve("real").also(Files::createDirectory)
        val symlink = temporaryDirectory.resolve("linked")
        runCatching {
            Files.createSymbolicLink(symlink, realDirectory)
        }.onFailure {
            Assumptions.assumeTrue(false, "Symbolic links unavailable: ${it.message}")
        }
        val calls = AtomicInteger()
        val client = dedicatedTestClient { request -> calls.incrementAndGet().let { response(request, 200, "payload".toResponseBody()) } }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { transport(client).download(request().copy(stagingDirectory = symlink)) }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `client with automatic redirects is rejected at construction`() {
        val unsafeClient = OkHttpClient.Builder().followRedirects(true).build()

        assertFailsWith<IllegalArgumentException> {
            DesktopExtensionArtifactTransport(unsafeClient, 1024)
        }
    }

    private fun request(
        url: String = HTTPS_URL,
        packageName: String = PACKAGE_NAME,
    ) = ExtensionArtifactTransport.Request(
        url = url,
        stagingDirectory = temporaryDirectory,
        packageName = packageName,
        kind = ArtifactKind.JAR,
    )

    private fun transport(
        client: OkHttpClient,
        maximumBytes: Long = 1024,
    ) = DesktopExtensionArtifactTransport(client, maximumBytes)

    private fun respondWith(body: ResponseBody): OkHttpClient = dedicatedTestClient { request -> response(request, 200, body) }

    private fun dedicatedTestClient(responder: (Request) -> Response): OkHttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain -> responder(chain.request()) }
            .build()

    private fun response(
        request: Request,
        status: Int,
        body: ResponseBody,
        location: String? = null,
        contentEncoding: String? = null,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("fixture")
            .body(body)
            .apply {
                location?.let { header("Location", it) }
                contentEncoding?.let { header("Content-Encoding", it) }
            }.build()

    private fun stagingFiles(): List<Path> = Files.list(temporaryDirectory).use { it.toList() }

    private class UnknownLengthBody(
        payload: ByteArray,
    ) : ResponseBody() {
        private val body = Buffer().write(payload)

        override fun contentLength(): Long = -1

        override fun contentType(): MediaType? = null

        override fun source(): BufferedSource = body
    }

    private companion object {
        const val HTTPS_URL = "https://cdn.invalid/extension.jar"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.fixture"
    }
}
