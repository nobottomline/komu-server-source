/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A cover fetch the egress policy refused before any request reached the destination. */
class CoverEgressRefused(
    reason: String,
) : IOException(reason)

/**
 * Egress policy for every request the full-size cover check makes
 * (ADR-0084 Review 1). Candidate URLs are derived from extension data, so a
 * source, or anyone who controls its cover host, could otherwise use the
 * server as a confused deputy: a `302` to `127.0.0.1`, the LAN or another
 * domain would be followed with the source's headers.
 *
 * - Redirects are never followed by OkHttp; each hop is checked here and
 *   issued as a new request, at most [maximumRedirects] times.
 * - Every hop must use `https` (when [requireHttps]) and a host in the
 *   candidate's allowed set; the source's headers therefore never reach
 *   another host.
 * - Every address a hop connects to must pass [isAllowedAddress]: the
 *   resolver result is checked before connecting (each hop resolves again,
 *   so DNS rebinding between hops is caught) and the connected peer is
 *   checked again before the request is written (IP literals and reused
 *   connections included).
 *
 * With an explicitly configured proxy (desktop or test only; the iOS
 * runtime has none) the target is resolved by the proxy, so only the host
 * and scheme checks apply.
 */
class CoverEgress(
    private val requireHttps: Boolean = true,
    private val isAllowedAddress: (InetAddress) -> Boolean = ::isPublicAddress,
    private val maximumRedirects: Int = MAXIMUM_REDIRECTS,
) {
    /**
     * GETs [url] with [client] and [headers], following at most
     * [maximumRedirects] redirects within [allowedHosts]. Throws
     * [CoverEgressRefused] when a hop is refused and [IOException] on
     * network failure; any other outcome is a [CoverFetch].
     */
    suspend fun get(
        client: OkHttpClient,
        headers: Headers,
        url: String,
        allowedHosts: Set<String>,
        maximumBytes: Int,
    ): CoverFetch {
        val hosts = allowedHosts.mapTo(HashSet()) { it.lowercase() }
        var current = url.toHttpUrlOrNull() ?: throw CoverEgressRefused("Not an HTTP URL")
        var redirects = 0
        while (true) {
            checkHop(current, hosts)
            val guard = Guard(client.dns)
            val guarded =
                client
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .dns(guard)
                    .addNetworkInterceptor(guard)
                    .build()
            val response =
                try {
                    guarded.newCall(GET(current, headers, cache = CacheControl.FORCE_NETWORK)).awaitAnyStatus()
                } catch (error: IOException) {
                    guard.refusal?.let { throw CoverEgressRefused(it) }
                    throw error
                }
            val next =
                response.use {
                    if (it.code !in REDIRECT_CODES) return read(it, maximumBytes)
                    val location = it.header("Location") ?: return CoverFetch(it.code)
                    current.resolve(location) ?: throw CoverEgressRefused("Unusable redirect location")
                }
            if (++redirects > maximumRedirects) throw CoverEgressRefused("More than $maximumRedirects redirects")
            current = next
        }
    }

    /** Scheme, host and (for an IP literal) address of one hop. */
    fun checkHop(
        url: HttpUrl,
        allowedHosts: Set<String>,
    ) {
        if (url.scheme != "https" && (requireHttps || url.scheme != "http")) throw CoverEgressRefused("Scheme ${url.scheme} refused")
        if (url.host.lowercase() !in allowedHosts) throw CoverEgressRefused("Host outside the source's cover host")
        literalAddress(url.host)?.let { if (!isAllowedAddress(it)) throw CoverEgressRefused("Address refused") }
    }

    private fun read(
        response: Response,
        maximumBytes: Int,
    ): CoverFetch {
        if (!response.isSuccessful) return CoverFetch(response.code)
        val body = response.body
        if (body.contentLength() > maximumBytes) return CoverFetch(413)
        val bytes = body.byteStream().use { input -> input.readNBytes(maximumBytes + 1) }
        if (bytes.size > maximumBytes) return CoverFetch(413)
        return CoverFetch(response.code, bytes, response.header("Content-Type"))
    }

    /** One hop's resolver and peer check; remembers why it refused. */
    private inner class Guard(
        private val delegate: Dns,
    ) : Dns,
        Interceptor {
        @Volatile
        var refusal: String? = null
            private set

        private fun refuse(reason: String): Nothing {
            refusal = reason
            throw CoverEgressRefused(reason)
        }

        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = delegate.lookup(hostname)
            // One non-public answer refuses the host: a mixed answer is how
            // rebinding attacks pick the address the connection lands on.
            if (addresses.isEmpty() || addresses.any { !isAllowedAddress(it) }) refuse("Resolved address refused")
            return addresses
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val route = chain.connection()?.route() ?: refuse("No connection route")
            if (route.proxy.type() == Proxy.Type.DIRECT) {
                val address = route.socketAddress.address ?: refuse("Unresolved peer")
                if (!isAllowedAddress(address)) refuse("Peer address refused")
            }
            return chain.proceed(chain.request())
        }
    }

    companion object {
        const val MAXIMUM_REDIRECTS = 3

        /**
         * Enqueues the call and returns its response whatever the status.
         * (Suwayomi's `await` turns every non-2xx answer, redirects
         * included, into a bare exception, which would hide a 404 as a
         * network error and a 302 from this policy.)
         */
        private suspend fun Call.awaitAnyStatus(): Response =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { cancel() } }
                enqueue(
                    object : Callback {
                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            continuation.resume(response) { _, _, _ -> response.close() }
                        }

                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (!continuation.isCancelled) continuation.resumeWithException(e)
                        }
                    },
                )
            }

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** The address of an IP-literal host (OkHttp does not resolve those through [Dns]). */
        internal fun literalAddress(host: String): InetAddress? {
            val bare = host.removePrefix("[").removeSuffix("]")
            val isLiteral =
                bare.contains(':') || (bare.isNotEmpty() && bare.all { it.isDigit() || it == '.' } && bare.count { it == '.' } == 3)
            return if (isLiteral) runCatching { InetAddress.getByName(bare) }.getOrNull() else null
        }

        /**
         * Whether [address] is a globally routable unicast address: not
         * loopback, unspecified, private (RFC 1918), shared (RFC 6598),
         * link-local, unique-local, site-local, multicast, reserved or
         * documentation space, including those embedded in IPv4-mapped,
         * IPv4-compatible, NAT64 and 6to4 IPv6 addresses.
         */
        fun isPublicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            return when (bytes.size) {
                4 -> isPublicV4(bytes)
                16 -> isPublicV6(bytes)
                else -> false
            }
        }

        private fun isPublicV4(b: ByteArray): Boolean {
            val a0 = b[0].toInt() and 0xff
            val a1 = b[1].toInt() and 0xff
            val a2 = b[2].toInt() and 0xff
            return when {
                a0 == 0 -> false

                // "this network"
                a0 == 10 -> false

                a0 == 100 && a1 in 64..127 -> false

                // shared address space
                a0 == 127 -> false

                a0 == 169 && a1 == 254 -> false

                // link-local, cloud metadata
                a0 == 172 && a1 in 16..31 -> false

                a0 == 192 && a1 == 0 && a2 == 0 -> false

                // IETF protocol assignments
                a0 == 192 && a1 == 0 && a2 == 2 -> false

                // TEST-NET-1
                a0 == 192 && a1 == 88 && a2 == 99 -> false

                // 6to4 relay anycast
                a0 == 192 && a1 == 168 -> false

                a0 == 198 && a1 in 18..19 -> false

                // benchmarking
                a0 == 198 && a1 == 51 && a2 == 100 -> false

                // TEST-NET-2
                a0 == 203 && a1 == 0 && a2 == 113 -> false

                // TEST-NET-3
                a0 >= 224 -> false

                // multicast, reserved, broadcast
                else -> true
            }
        }

        private fun isPublicV6(b: ByteArray): Boolean {
            val first = b[0].toInt() and 0xff
            val second = b[1].toInt() and 0xff
            val v4Tail = b.copyOfRange(12, 16)

            fun zero(range: IntRange) = range.all { b[it].toInt() == 0 }
            return when {
                // ::, ::1 and IPv4-compatible ::a.b.c.d
                zero(0..11) -> {
                    false
                }

                // IPv4-mapped ::ffff:a.b.c.d
                zero(0..9) && b[10] == 0xff.toByte() && b[11] == 0xff.toByte() -> {
                    isPublicV4(v4Tail)
                }

                // NAT64 well-known prefix 64:ff9b::/96 and the local-use 64:ff9b:1::/48
                first == 0x00 && second == 0x64 && b[2] == 0xff.toByte() && b[3] == 0x9b.toByte() -> {
                    zero(4..11) && isPublicV4(v4Tail)
                }

                // 6to4 2002:a.b.c.d::/48
                first == 0x20 && second == 0x02 -> {
                    isPublicV4(b.copyOfRange(2, 6))
                }

                // Teredo 2001::/32 hides the peer's IPv4; documentation 2001:db8::/32
                first == 0x20 && second == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0 -> {
                    false
                }

                first == 0x20 && second == 0x01 && b[2] == 0x0d.toByte() && b[3] == 0xb8.toByte() -> {
                    false
                }

                // Discard-only 100::/64
                first == 0x01 && second == 0x00 && zero(2..7) -> {
                    false
                }

                first and 0xfe == 0xfc -> {
                    false
                }

                // unique local fc00::/7
                first == 0xfe && second and 0xc0 == 0x80 -> {
                    false
                }

                // link-local fe80::/10
                first == 0xfe && second and 0xc0 == 0xc0 -> {
                    false
                }

                // site-local fec0::/10
                first == 0xff -> {
                    false
                }

                // multicast
                else -> {
                    first and 0xe0 == 0x20
                } // global unicast 2000::/3 only
            }
        }
    }
}
