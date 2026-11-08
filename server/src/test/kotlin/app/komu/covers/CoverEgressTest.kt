package app.komu.covers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The candidate-fetch egress policy against real sockets: JDK HTTP servers
 * on loopback stand in for the source's cover host ("cdn.test") and for an
 * internal service an attacker would aim a redirect at. Name resolution is
 * scripted, so each case decides which address a name resolves to.
 */
class CoverEgressTest {
    private class Fixture(
        address: InetAddress,
    ) {
        val requests = CopyOnWriteArrayList<Pair<String, Headers>>()
        val routes = mutableMapOf<String, (HttpExchange) -> Unit>()
        val server: HttpServer =
            HttpServer.create(InetSocketAddress(address, 0), 0).apply {
                createContext("/") { exchange ->
                    val headers = Headers.Builder().apply { exchange.requestHeaders.forEach { (k, v) -> v.forEach { add(k, it) } } }.build()
                    requests += exchange.requestURI.path to headers
                    val route = routes[exchange.requestURI.path]
                    if (route == null) exchange.sendResponseHeaders(404, -1) else route(exchange)
                    exchange.close()
                }
                start()
            }
        val port get() = server.address.port

        fun redirect(
            path: String,
            location: String,
            close: Boolean = false,
        ) {
            routes[path] = { exchange ->
                exchange.responseHeaders.add("Location", location)
                if (close) exchange.responseHeaders.add("Connection", "close")
                exchange.sendResponseHeaders(302, -1)
            }
        }

        fun image(
            path: String,
            bytes: ByteArray,
        ) {
            routes[path] = { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
    }

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val cdn = Fixture(loopback) // the source's cover host
    private val internal = Fixture(loopback) // what a hostile redirect aims at
    private val bytes = byteArrayOf(1, 2, 3, 4)

    /** Scripted resolver: each name answers its queue in turn (the last answer repeats). */
    private val answers = mutableMapOf<String, ArrayDeque<List<InetAddress>>>()
    private val lookups = mutableListOf<String>()
    private val dns =
        Dns { host ->
            lookups += host
            val queue = answers[host] ?: throw java.net.UnknownHostException(host)
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    private val client = OkHttpClient.Builder().dns(dns).build()
    private val sourceHeaders = Headers.headersOf("Referer", "https://source.test/", "X-Source-Token", "source-only")

    private fun resolve(
        host: String,
        vararg addresses: String,
    ) {
        answers[host] = ArrayDeque(addresses.map { listOf(InetAddress.getByName(it)) })
    }

    /** The fixtures listen on 127.0.0.1, so for "cdn.test" that address stands in for a public one. */
    private val fixturePolicy = CoverEgress(requireHttps = false, isAllowedAddress = { it == loopback || CoverEgress.isPublicAddress(it) })
    private val productionAddressPolicy = CoverEgress(requireHttps = false)

    private fun fetch(
        egress: CoverEgress,
        url: String,
        hosts: Set<String> = setOf("cdn.test"),
    ) = runBlocking { egress.get(client, sourceHeaders, url, hosts, 1 shl 20) }

    @AfterEach
    fun stop() {
        cdn.server.stop(0)
        internal.server.stop(0)
    }

    @Test
    fun `a same-host redirect is followed and every hop carries only the source's headers`() {
        resolve("cdn.test", "127.0.0.1")
        cdn.redirect("/cover-193x278.jpg", "/cover.jpg")
        cdn.image("/cover.jpg", bytes)
        val fetched = fetch(fixturePolicy, "http://cdn.test:${cdn.port}/cover-193x278.jpg")
        assertEquals(200, fetched.status)
        assertArrayEquals(bytes, fetched.bytes)
        assertEquals(listOf("/cover-193x278.jpg", "/cover.jpg"), cdn.requests.map { it.first })
        cdn.requests.forEach { (_, headers) -> assertEquals("source-only", headers["X-Source-Token"]) }
    }

    @Test
    fun `a redirect to loopback is refused before any request reaches it`() {
        resolve("cdn.test", "127.0.0.1")
        cdn.redirect("/c.jpg", "http://127.0.0.1:${internal.port}/admin")
        assertThrows<CoverEgressRefused> { fetch(fixturePolicy, "http://cdn.test:${cdn.port}/c.jpg") }
        assertTrue(internal.requests.isEmpty())
        // Also when the loopback literal is somehow an allowed host: the address is refused.
        assertThrows<CoverEgressRefused> {
            fetch(productionAddressPolicy, "http://127.0.0.1:${internal.port}/admin", setOf("127.0.0.1"))
        }
        assertTrue(internal.requests.isEmpty())
    }

    @Test
    fun `a redirect to the LAN is refused`() {
        resolve("cdn.test", "127.0.0.1")
        resolve("router.lan", "192.168.1.1")
        for (location in listOf(
            "http://192.168.1.1/",
            "http://router.lan:${internal.port}/",
            "http://[fd00::1]/",
            "http://169.254.169.254/latest/meta-data",
        )) {
            cdn.redirect("/c.jpg", location)
            assertThrows<CoverEgressRefused>(location) { fetch(fixturePolicy, "http://cdn.test:${cdn.port}/c.jpg") }
        }
        assertFalse("router.lan" in lookups, "a refused host is not even resolved")
        assertTrue(internal.requests.isEmpty())
    }

    @Test
    fun `a redirect to another domain is refused and the source's headers never leave`() {
        resolve("cdn.test", "127.0.0.1")
        resolve("evil.test", "127.0.0.1")
        cdn.redirect("/c.jpg", "http://evil.test:${internal.port}/steal")
        val error = assertThrows<CoverEgressRefused> { fetch(fixturePolicy, "http://cdn.test:${cdn.port}/c.jpg") }
        assertEquals("Host outside the source's cover host", error.message)
        assertTrue(internal.requests.isEmpty())
        assertFalse("evil.test" in lookups)
    }

    @Test
    fun `an allowed host that resolves to a non-public address is refused without a request`() {
        for (address in listOf(
            "127.0.0.1",
            "10.0.0.5",
            "172.16.3.4",
            "192.168.0.10",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "::1",
            "fd12::1",
            "fe80::1",
            "::ffff:127.0.0.1",
            "::ffff:10.0.0.1",
        )) {
            resolve("cdn.test", address)
            cdn.image("/c.jpg", bytes)
            assertThrows<CoverEgressRefused>(address) { fetch(productionAddressPolicy, "http://cdn.test:${cdn.port}/c.jpg") }
        }
        assertTrue(cdn.requests.isEmpty(), "the fixture on 127.0.0.1 was never reached")
    }

    @Test
    fun `a host that resolves to a public and a private address is refused`() {
        answers["cdn.test"] = ArrayDeque(listOf(listOf(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("127.0.0.1"))))
        assertThrows<CoverEgressRefused> { fetch(productionAddressPolicy, "http://cdn.test:${cdn.port}/c.jpg") }
        assertTrue(cdn.requests.isEmpty())
    }

    @Test
    fun `each hop resolves again so DNS rebinding between hops is refused`() {
        // First answer is the "public" fixture, the second an internal address.
        resolve("cdn.test", "127.0.0.1", "10.0.0.7")
        cdn.redirect("/c.jpg", "/original.jpg", close = true)
        cdn.image("/original.jpg", bytes)
        assertThrows<CoverEgressRefused> { fetch(fixturePolicy, "http://cdn.test:${cdn.port}/c.jpg") }
        assertEquals(listOf("/c.jpg"), cdn.requests.map { it.first })
        assertEquals(listOf("cdn.test", "cdn.test"), lookups)
    }

    @Test
    fun `redirects are bounded`() {
        resolve("cdn.test", "127.0.0.1")
        for (i in 0..5) cdn.redirect("/r$i", "/r${i + 1}")
        val error = assertThrows<CoverEgressRefused> { fetch(fixturePolicy, "http://cdn.test:${cdn.port}/r0") }
        assertEquals("More than ${CoverEgress.MAXIMUM_REDIRECTS} redirects", error.message)
        assertEquals(CoverEgress.MAXIMUM_REDIRECTS + 1, cdn.requests.size)
    }

    @Test
    fun `every hop must be https in production, so a downgrade is refused`() {
        val egress = CoverEgress()
        resolve("cdn.test", "127.0.0.1")
        cdn.image("/c.jpg", bytes)
        assertThrows<CoverEgressRefused> { fetch(egress, "http://cdn.test:${cdn.port}/c.jpg") }
        assertTrue(cdn.requests.isEmpty())
        // The same check runs on every redirect target.
        egress.checkHop("https://cdn.test/c.jpg".toHttpUrl(), setOf("cdn.test"))
        assertThrows<CoverEgressRefused> { egress.checkHop("http://cdn.test/c.jpg".toHttpUrl(), setOf("cdn.test")) }
        assertThrows<CoverEgressRefused> { egress.checkHop("https://other.test/c.jpg".toHttpUrl(), setOf("cdn.test")) }
        assertThrows<CoverEgressRefused> { egress.checkHop("https://[::1]/c.jpg".toHttpUrl(), setOf("[::1]", "::1")) }
    }

    @Test
    fun `public and non-public addresses are told apart, embedded IPv4 included`() {
        val public = listOf("93.184.215.14", "1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2002:5db8:d70e::1", "64:ff9b::5db8:d70e")
        val private =
            listOf(
                "0.0.0.0",
                "10.1.2.3",
                "100.64.0.1",
                "127.0.0.1",
                "127.255.0.1",
                "169.254.169.254",
                "172.16.0.1",
                "172.31.255.255",
                "192.0.0.8",
                "192.0.2.1",
                "192.168.1.1",
                "198.18.0.1",
                "198.51.100.2",
                "203.0.113.9",
                "224.0.0.1",
                "240.0.0.1",
                "255.255.255.255",
                "::",
                "::1",
                "::127.0.0.1",
                "::ffff:127.0.0.1",
                "::ffff:192.168.0.1",
                "64:ff9b::7f00:1",
                "64:ff9b:1::a00:1",
                "2002:7f00:1::1",
                "2002:c0a8:101::1",
                "2001::1",
                "2001:db8::1",
                "100::1",
                "fc00::1",
                "fd00::1",
                "fe80::1",
                "fec0::1",
                "ff02::1",
            )
        public.forEach { assertTrue(CoverEgress.isPublicAddress(InetAddress.getByName(it)), it) }
        private.forEach { assertFalse(CoverEgress.isPublicAddress(InetAddress.getByName(it)), it) }
    }
}
