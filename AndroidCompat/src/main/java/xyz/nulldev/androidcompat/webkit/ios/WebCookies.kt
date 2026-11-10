package xyz.nulldev.androidcompat.webkit.ios

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val logger = KotlinLogging.logger {}

/**
 * Komu (ADR-0087 Review 1): one cookie as RFC 6265 §5.3 stores it, exchanged between
 * the server's jar and a WebView session's own cookie store.
 *
 * - [domain] is canonical: lower case, no leading dot.
 * - [hostOnly]: set without a `Domain` attribute; sent to [domain] itself only.
 *   Otherwise it is sent to [domain] and its subdomains.
 * - [expiresAt]: epoch milliseconds (whole seconds); null for a session cookie.
 * - [sameSite]: `Strict`, `Lax`, `None`, or null when the server did not say.
 * - Identity ([key]) is (name, domain, path) (§5.3 step 11).
 */
data class WebCookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val sameSite: String?,
    val expiresAt: Long?,
) {
    data class Key(
        val name: String,
        val domain: String,
        val path: String,
    )

    val key: Key get() = Key(name, domain, path)

    fun isExpired(now: Long): Boolean = expiresAt != null && expiresAt <= now

    /** §5.4 step 1, the domain rule: whether this cookie is sent to [host]. */
    fun appliesTo(host: String): Boolean {
        val h = host.lowercase()
        return if (hostOnly) h == domain else RfcCookies.domainMatches(h, domain)
    }

    companion object {
        /** A cookie with canonical fields (domain lower case without a dot, expiry in whole seconds, SameSite spelling). */
        fun canonical(
            name: String,
            value: String,
            domain: String,
            hostOnly: Boolean,
            path: String?,
            secure: Boolean,
            httpOnly: Boolean,
            sameSite: String?,
            expiresAt: Long?,
        ) = WebCookie(
            name = name,
            value = value,
            domain = domain.lowercase().removePrefix("."),
            hostOnly = hostOnly,
            path = path?.takeIf { it.startsWith("/") } ?: "/",
            secure = secure,
            httpOnly = httpOnly,
            sameSite = RfcCookies.sameSite(sameSite),
            expiresAt = expiresAt?.let { Math.floorDiv(it, 1000L) * 1000L },
        )
    }
}

/** RFC 6265 rules the WebView cookie bridge applies. */
object RfcCookies {
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIpAddress(host: String): Boolean = ipv4.matches(host) || host.contains(':') || host.startsWith("[")

    /** §5.1.3 domain-match: identical, or [host] ends with `.` + [domain] and is not an IP address. */
    fun domainMatches(
        host: String,
        domain: String,
    ): Boolean = host == domain || (host.endsWith(".$domain") && !isIpAddress(host))

    fun sameSite(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            "strict" -> "Strict"
            "lax" -> "Lax"
            "none" -> "None"
            else -> null
        }
}

/** A jar record with the generation it had when read. */
data class VersionedCookie(
    val cookie: WebCookie,
    val generation: Long,
)

/**
 * The server's cookie jar as the WebView sessions see it. Implemented by the server
 * over its persistent jar (`PersistentCookieStore`) and installed in [WebViewCookies].
 *
 * Versioned (Codex recheck 1): every write or removal of an identity bumps its
 * generation, and [compareAndSet] applies a session's change only when the record
 * is still the one the session saw, atomically.
 */
interface WebViewCookieJar {
    /** Unexpired cookies the jar would send to [host] (§5.4 domain rule; the engine applies path and Secure per request). */
    fun cookiesFor(host: String): List<VersionedCookie>

    /** The identity's current generation; 0 when it was never written. */
    fun generation(key: WebCookie.Key): Long

    /** The latest generation the jar has handed out. */
    fun epoch(): Long

    /** Sets (null: removes) the record when its generation is still [expected]; its new generation, or null when it changed. */
    fun compareAndSet(
        key: WebCookie.Key,
        expected: Long,
        cookie: WebCookie?,
    ): Long?
}

/** Where the iOS provider finds the server's jar. */
object WebViewCookies {
    @Volatile
    var jar: WebViewCookieJar? = null
        private set

    @Volatile
    private var warned = false

    fun install(jar: WebViewCookieJar?) {
        this.jar = jar
    }

    internal fun jarOrWarn(): WebViewCookieJar? =
        jar ?: run {
            if (!warned) {
                warned = true
                logger.warn { "No server cookie jar installed: WebView sessions do not exchange cookies" }
            }
            null
        }
}

/** An in-memory [WebViewCookieJar] (tests and hosts without a persistent jar). */
class MemoryWebViewCookieJar(
    private val clock: () -> Long = System::currentTimeMillis,
) : WebViewCookieJar {
    private val cookies = LinkedHashMap<WebCookie.Key, WebCookie>()
    private val generations = HashMap<WebCookie.Key, Long>()
    private var epoch = 0L

    @Synchronized
    override fun cookiesFor(host: String): List<VersionedCookie> {
        val now = clock()
        return cookies.values.filter { !it.isExpired(now) && it.appliesTo(host) }.map { VersionedCookie(it, generations[it.key] ?: 0L) }
    }

    @Synchronized
    override fun generation(key: WebCookie.Key): Long = generations[key] ?: 0L

    @Synchronized
    override fun epoch(): Long = epoch

    @Synchronized
    override fun compareAndSet(
        key: WebCookie.Key,
        expected: Long,
        cookie: WebCookie?,
    ): Long? {
        if ((generations[key] ?: 0L) != expected) return null
        if (cookie == null) cookies.remove(key) else cookies[key] = cookie
        return (++epoch).also { generations[key] = it }
    }

    /** A server write (a response's Set-Cookie). */
    @Synchronized
    fun put(cookie: WebCookie) {
        cookies[cookie.key] = cookie
        generations[cookie.key] = ++epoch
    }

    @Synchronized
    fun find(key: WebCookie.Key): WebCookie? = cookies[key]?.takeIf { !it.isExpired(clock()) }

    @Synchronized
    fun all(): List<WebCookie> = cookies.values.toList()
}

/**
 * One session's cookie reconciliation with the server's jar.
 *
 * The session's store starts empty (its own non-persistent data store). Before each
 * load the jar's cookies for the load's host are copied in ([preload]). The engine
 * reports the session's full cookie set together with its admitted target hosts
 * whenever either changes ([reconcile]); the hosts are admitted before any of
 * their traffic, so a redirect's target is a target host before its cookies arrive.
 *
 * Target hosts: hosts the extension loaded, a data load's base host, and hosts the
 * main frame navigated to (redirects and `window.open` included). Sub-frame hosts
 * are not targets, so a third-party frame's cookies never enter the jar.
 *
 * The reconciliation is a set difference against [baseline] — the cookies, within
 * the targets' scope, that the session's store is known to hold (preloaded or
 * reported), each with the jar generation it was seen at:
 * - a cookie that is new or changed is stored in the jar (host-only stays host-only,
 *   every attribute kept);
 * - a cookie that disappeared or expired is removed from the jar;
 * both only by compare-and-set against the generation the session saw (for a new
 * identity: unwritten since the session last synchronised). A record a server
 * response wrote meanwhile (a rotated token) is never overwritten or deleted by
 * an older report, whatever its value. A report taken before a preload reached
 * the store does not judge that preload's records.
 * Cookies outside the scope are never written or deleted.
 */
internal class CookieSync(
    private val jar: () -> WebViewCookieJar?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the session's store is known to hold for one identity: the record, its jar generation, the preload that put it there (0: a report). */
    private data class Seen(
        val cookie: WebCookie,
        val generation: Long,
        val preload: Long,
    )

    private val hosts = LinkedHashSet<String>()
    private val baseline = HashMap<WebCookie.Key, Seen>()
    private var nextPreload = 0L

    /** The jar's epoch when this session last synchronised: a record newer than it was written by someone else since. */
    private var syncedAt = 0L

    @Synchronized
    fun targetHosts(): Set<String> = hosts.toSet()

    /**
     * The jar's cookies for [host], copied into the session before a load of it;
     * [host] becomes a target. Returns the preload's sequence number (the engine
     * reports it back once those cookies are in the session's store) and the cookies.
     */
    @Synchronized
    fun preload(host: String?): Pair<Long, List<WebCookie>> {
        val sequence = ++nextPreload
        val jar = jar()
        if (host.isNullOrEmpty() || jar == null) return sequence to emptyList()
        hosts += host.lowercase()
        if (baseline.isEmpty()) syncedAt = jar.epoch()
        val cookies = jar.cookiesFor(host)
        for ((cookie, generation) in cookies) baseline[cookie.key] = Seen(cookie, generation, sequence)
        return sequence to cookies.map { it.cookie }
    }

    /**
     * A report of the session's full cookie set. [preloadsDone]: the last preload the
     * store holds; records of a later preload are not judged by this (older) report.
     */
    @Synchronized
    fun reconcile(
        admittedHosts: Collection<String>,
        snapshot: List<WebCookie>,
        preloadsDone: Long = Long.MAX_VALUE,
    ) {
        admittedHosts.forEach { if (it.isNotEmpty()) hosts += it.lowercase() }
        val jar = jar() ?: return
        val now = clock()
        val present = HashMap<WebCookie.Key, WebCookie>()
        for (cookie in snapshot) {
            if (cookie.isExpired(now) || !inScope(cookie)) continue
            present[cookie.key] = cookie
            val seen = baseline[cookie.key]
            if (seen != null && (seen.preload > preloadsDone || engineEquivalent(seen.cookie, cookie))) continue
            // Unchanged in the jar since the session saw it: the page's change applies.
            // Otherwise a server response wrote it meanwhile and wins.
            val expected =
                seen?.generation ?: jar.generation(cookie.key).takeIf { it <= syncedAt }
            val written = expected?.let { jar.compareAndSet(cookie.key, it, cookie) }
            baseline[cookie.key] = Seen(cookie, written ?: jar.generation(cookie.key), 0)
        }
        for (key in baseline.keys.filter { it !in present }) {
            val seen = baseline.getValue(key)
            if (seen.preload > preloadsDone) continue // its preload has not reached the store yet
            baseline.remove(key)
            jar.compareAndSet(key, seen.generation, null)
        }
        syncedAt = jar.epoch()
    }

    private fun inScope(cookie: WebCookie): Boolean = hosts.any { cookie.appliesTo(it) }

    /** Equal as WebKit stores them: it represents SameSite Lax and Strict only, so None reads back as unspecified. */
    private fun engineEquivalent(
        a: WebCookie,
        b: WebCookie,
    ): Boolean = a == b || a.copy(sameSite = a.sameSite.takeUnless { it == "None" }) == b.copy(sameSite = b.sameSite.takeUnless { it == "None" })
}

/** The wire form of cookies between the provider and the engine (a JSON array of objects). */
internal object CookieWire {
    fun encode(cookies: List<WebCookie>): String =
        JsonArray(
            cookies.map { c ->
                JsonObject(
                    mapOf(
                        "n" to JsonPrimitive(c.name),
                        "v" to JsonPrimitive(c.value),
                        "d" to JsonPrimitive(c.domain),
                        "h" to JsonPrimitive(c.hostOnly),
                        "p" to JsonPrimitive(c.path),
                        "s" to JsonPrimitive(c.secure),
                        "o" to JsonPrimitive(c.httpOnly),
                        "ss" to (c.sameSite?.let(::JsonPrimitive) ?: JsonNull),
                        "e" to (c.expiresAt?.let { JsonPrimitive(it / 1000L) } ?: JsonNull),
                    ),
                )
            },
        ).toString()

    /** Decodes [json]; entries that are not well-formed cookies are skipped. */
    fun decode(json: String): List<WebCookie> {
        if (json.isEmpty()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                val name = o["n"]!!.jsonPrimitive.content
                val domain = o["d"]!!.jsonPrimitive.content
                require(name.isNotEmpty() && domain.isNotEmpty())
                WebCookie.canonical(
                    name = name,
                    value = o["v"]?.jsonPrimitive?.content.orEmpty(),
                    domain = domain,
                    hostOnly = o["h"]?.jsonPrimitive?.booleanOrNull ?: true,
                    path = o["p"]?.jsonPrimitive?.contentOrNull,
                    secure = o["s"]?.jsonPrimitive?.booleanOrNull ?: false,
                    httpOnly = o["o"]?.jsonPrimitive?.booleanOrNull ?: false,
                    sameSite = (o["ss"] as? JsonPrimitive)?.contentOrNull,
                    expiresAt = (o["e"] as? JsonPrimitive)?.longOrNull?.let { it * 1000L },
                )
            }.getOrNull()
        }
    }

    fun decodeHosts(json: String): List<String> =
        runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
        }.getOrDefault(emptyList())
}
