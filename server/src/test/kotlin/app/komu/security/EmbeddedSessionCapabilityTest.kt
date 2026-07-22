package app.komu.security

import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddedSessionCapabilityTest {
    private val token =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(SessionCapability.BYTE_COUNT) { it.toByte() })

    @Test
    fun `valid verifier accepts only the exact token`() {
        val capability = SessionCapability.fromBootstrapToken(token)

        assertTrue(capability.matches(token))
        assertEquals(false, capability.matches(null))
        assertEquals(false, capability.matches(""))
        assertEquals(false, capability.matches(token.dropLast(1) + "A"))
    }

    @Test
    fun `bootstrap rejects malformed or undersized values`() {
        assertFailsWith<IllegalArgumentException> {
            SessionCapability.fromBootstrapToken("not-a-capability")
        }
        assertFailsWith<IllegalArgumentException> {
            SessionCapability.fromBootstrapToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16)),
            )
        }
    }

    @Test
    fun `jetty boundary returns the same generic 401 before any endpoint`() {
        val capability = SessionCapability.fromBootstrapToken(token)
        val server = Server()
        val connector =
            ServerConnector(server).apply {
                host = "127.0.0.1"
                port = 0
            }
        server.addConnector(connector)

        val terminal =
            object : Handler.Abstract() {
                override fun handle(
                    request: Request,
                    response: Response,
                    callback: Callback,
                ): Boolean {
                    response.status = 200
                    response.headers.put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8")
                    Content.Sink.write(response, true, "protected-content", callback)
                    return true
                }
            }
        val boundary = EmbeddedCapabilityHandler(capability::matches)
        boundary.handler = terminal
        server.handler = boundary
        server.start()

        try {
            val endpoint = URI("http://127.0.0.1:${connector.localPort}/api/v1/private")
            val client = HttpClient.newHttpClient()

            val missing = client.send(request(endpoint), HttpResponse.BodyHandlers.ofString())
            val wrong =
                client.send(
                    request(endpoint, token.dropLast(1) + "A"),
                    HttpResponse.BodyHandlers.ofString(),
                )
            val authorized =
                client.send(
                    request(endpoint, token),
                    HttpResponse.BodyHandlers.ofString(),
                )

            assertEquals(401, missing.statusCode())
            assertEquals(401, wrong.statusCode())
            assertEquals(missing.body(), wrong.body())
            assertEquals("Unauthorized", missing.body())
            assertEquals(200, authorized.statusCode())
            assertEquals("protected-content", authorized.body())
        } finally {
            server.stop()
            server.join()
        }
    }

    private fun request(
        endpoint: URI,
        capability: String? = null,
    ): HttpRequest =
        HttpRequest
            .newBuilder(endpoint)
            .apply {
                capability?.let { header(EmbeddedSessionCapability.HEADER_NAME, it) }
            }.GET()
            .build()
}
