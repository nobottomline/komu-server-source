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
 * Komu ADR-0087 Recheck 2: one cookie identity's three-way merge, a pure function.
 *
 * - [base]: the record last known to be present in BOTH the jar and the session's
 *   store (null: none is known to be in both);
 * - [server]: the jar's record now (null: absent), at [serverGeneration];
 * - [view]: the session's record in a snapshot (null: absent).
 *
 * Rules (records compared in full, as WebKit stores them):
 * 1. view = server: converged; base := server.
 * 2. server = base, view ≠ server: the page changed it. The jar takes the page's
 *    record — a deletion only here, when the store lacks it and the jar still
 *    holds exactly the base — by compare-and-set against [serverGeneration];
 *    base := view once that succeeds.
 * 3. otherwise (the server changed since base, or both did): the server wins; its
 *    record (or its absence) is pushed into the store; base does not move until a
 *    snapshot shows the store holding it.
 */
internal object CookieMerge {
    sealed interface JarAction {
        data object None : JarAction

        /** Store [cookie] (null: remove) if the identity is still at [expected]. */
        data class CompareAndSet(
            val cookie: WebCookie?,
            val expected: Long,
        ) : JarAction
    }

    sealed interface ViewAction {
        data object None : ViewAction

        data class Set(
            val cookie: WebCookie,
        ) : ViewAction

        data object Delete : ViewAction
    }

    data class Outcome(
        val jar: JarAction,
        val view: ViewAction,
        /** The base after the actions succeed. */
        val base: WebCookie?,
    )

    /** Equal as WebKit stores them: it represents SameSite Lax and Strict only, so None reads back as unspecified. */
    fun same(
        a: WebCookie?,
        b: WebCookie?,
    ): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a == b || a.copy(sameSite = a.sameSite.takeUnless { it == "None" }) == b.copy(sameSite = b.sameSite.takeUnless { it == "None" })
    }

    fun reduce(
        base: WebCookie?,
        server: WebCookie?,
        serverGeneration: Long,
        view: WebCookie?,
    ): Outcome =
        when {
            same(view, server) -> Outcome(JarAction.None, ViewAction.None, server)
            same(server, base) -> Outcome(JarAction.CompareAndSet(view, serverGeneration), ViewAction.None, view)
            else -> Outcome(JarAction.None, server?.let { ViewAction.Set(it) } ?: ViewAction.Delete, base)
        }
}

/** Cookies for a session's store: records to set and identities to delete, in that order. */
data class CookiePush(
    val set: List<WebCookie>,
    val delete: List<WebCookie>,
) {
    fun isEmpty(): Boolean = set.isEmpty() && delete.isEmpty()

    companion object {
        val NONE = CookiePush(emptyList(), emptyList())
    }
}

/**
 * One session's cookie reconciliation with the server's jar (Komu ADR-0087 Recheck 2).
 *
 * Target hosts: hosts the extension loaded, a data load's base host, and hosts the
 * main frame navigated to (redirects and `window.open` included), reported by the
 * engine before their traffic. Sub-frame hosts are not targets, so a third-party
 * frame's cookies never enter the jar. Cookies outside the targets' scope are never
 * written or deleted.
 *
 * Every identity in scope is merged by [CookieMerge.reduce] from its base (the
 * record last seen in both the jar and the session's store), the jar's record and
 * the snapshot's. Jar writes are compare-and-set against the generation read in
 * the same pass, so a server response that lands meanwhile wins; a refused write
 * leaves the base where it was. Server changes are pushed into the store
 * ([CookiePush], before a load and after a snapshot), so the two converge; the base
 * only moves when a snapshot shows the store holding the record.
 *
 * The engine applies preloads, pushes and snapshots in one serial order per session,
 * so a snapshot shows every push submitted before it.
 */
internal class CookieSync(
    private val jar: () -> WebViewCookieJar?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hosts = LinkedHashSet<String>()
    private val base = HashMap<WebCookie.Key, WebCookie>()
    private var nextPreload = 0L

    @Synchronized
    fun targetHosts(): Set<String> = hosts.toSet()

    /** Bases on record (tests). */
    @Synchronized
    internal fun baseFor(key: WebCookie.Key): WebCookie? = base[key]

    /**
     * Before a load of [host], which becomes a target: the server's changes since the
     * base for that host's cookies, to push into the store (records the store already
     * holds are not rewritten, so an unreported page change is not lost). Returns the
     * preload's sequence number and the push.
     */
    @Synchronized
    fun preload(host: String?): Pair<Long, CookiePush> {
        val sequence = ++nextPreload
        val jar = jar()
        if (host.isNullOrEmpty() || jar == null) return sequence to CookiePush.NONE
        val target = host.lowercase()
        hosts += target
        val now = clock()
        val server = jar.cookiesFor(target).filter { !it.cookie.isExpired(now) }.associateBy { it.cookie.key }
        val set = server.values.map { it.cookie }.filter { !CookieMerge.same(base[it.key], it) }
        val delete = base.values.filter { it.appliesTo(target) && it.key !in server }
        return sequence to CookiePush(set, delete)
    }

    /**
     * A snapshot of the session's full cookie set, taken after every push submitted
     * before it. Applies the page's changes to the jar and returns the server's changes
     * to push into the store.
     */
    @Synchronized
    fun reconcile(
        admittedHosts: Collection<String>,
        snapshot: List<WebCookie>,
        @Suppress("UNUSED_PARAMETER") preloadsDone: Long = Long.MAX_VALUE,
    ): CookiePush {
        admittedHosts.forEach { if (it.isNotEmpty()) hosts += it.lowercase() }
        val jar = jar() ?: return CookiePush.NONE
        val now = clock()
        val view = snapshot.filter { !it.isExpired(now) && inScope(it) }.associateBy { it.key }
        val server = HashMap<WebCookie.Key, VersionedCookie>()
        for (host in hosts) {
            for (record in jar.cookiesFor(host)) if (!record.cookie.isExpired(now)) server[record.cookie.key] = record
        }
        val set = ArrayList<WebCookie>()
        val delete = ArrayList<WebCookie>()
        for (key in (base.keys + view.keys + server.keys).toSet()) {
            var serverRecord = server[key]
            var outcome = merge(key, serverRecord, view[key], jar)
            if (outcome.jar is CookieMerge.JarAction.CompareAndSet) {
                val action = outcome.jar as CookieMerge.JarAction.CompareAndSet
                if (jar.compareAndSet(key, action.expected, action.cookie) == null) {
                    // A server write landed meanwhile: it wins. Merge again against it.
                    serverRecord = jar.cookiesFor(key.domain).firstOrNull { it.cookie.key == key && !it.cookie.isExpired(now) }
                    outcome = merge(key, serverRecord, view[key], jar)
                    if (outcome.jar !is CookieMerge.JarAction.None) {
                        // Changed again while we looked: leave everything for the next snapshot.
                        continue
                    }
                }
            }
            when (val action = outcome.view) {
                is CookieMerge.ViewAction.Set -> set += action.cookie
                CookieMerge.ViewAction.Delete -> view[key]?.let { delete += it }
                CookieMerge.ViewAction.None -> Unit
            }
            if (outcome.base == null) base.remove(key) else base[key] = outcome.base
        }
        return CookiePush(set, delete)
    }

    private fun merge(
        key: WebCookie.Key,
        server: VersionedCookie?,
        view: WebCookie?,
        jar: WebViewCookieJar,
    ): CookieMerge.Outcome = CookieMerge.reduce(base[key], server?.cookie, server?.generation ?: jar.generation(key), view)

    private fun inScope(cookie: WebCookie): Boolean = hosts.any { cookie.appliesTo(it) }
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
