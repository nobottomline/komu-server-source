package app.komu.covers

import app.komu.covers.SyntheticCovers.art
import app.komu.covers.SyntheticCovers.cropped
import app.komu.covers.SyntheticCovers.jpeg
import app.komu.covers.SyntheticCovers.png
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** The runtime qualification entry point (driven through JNI on iOS) on the desktop decoder. */
class CoverQualificationTest {
    @Test
    fun `the qualification passes against a loopback fixture and the egress probes never reach it`() {
        val art = art(seed = 21)
        val files =
            mapOf(
                "/q/thumbnail.jpg" to jpeg(cropped(art, 193, 278)),
                "/q/original.jpg" to jpeg(art),
                "/q/other.jpg" to jpeg(art(seed = 77)),
                "/q/original.png" to png(art),
            )
        val requests = CopyOnWriteArrayList<String>()
        val server =
            HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
                createContext("/") { exchange ->
                    requests += exchange.requestURI.path
                    val body = files[exchange.requestURI.path]
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                    exchange.close()
                }
                start()
            }
        try {
            val report = Json.parseToJsonElement(CoverQualification.run("http://127.0.0.1:${server.address.port}/")).jsonObject
            assertTrue(report.getValue("ok").jsonPrimitive.boolean, report.toString())
            assertEquals("ACCEPTED", report.getValue("originalVerdict").jsonPrimitive.content)
            assertEquals("ORIGINAL", report.getValue("originalFinalState").jsonPrimitive.content)
            assertEquals("DIFFERENT_PICTURE", report.getValue("otherVerdict").jsonPrimitive.content)
            assertEquals("FORMAT_MISMATCH", report.getValue("pngVerdict").jsonPrimitive.content)
            listOf("egressLiteralLoopback", "egressLocalhostName", "egressHttpsRequired").forEach {
                assertTrue(
                    report
                        .getValue(it)
                        .jsonPrimitive.content
                        .startsWith("refused"),
                    "$it: $report",
                )
            }
            assertTrue(requests.none { it.startsWith("/egress/") }, "refused probes never reach the fixture: $requests")
        } finally {
            server.stop(0)
        }
    }
}
