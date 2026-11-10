package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.withLock
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// from TachiWeb-Server
class PersistentCookieStore(
    context: Context,
) : CookieStore {
    private val cookieMap = mutableMapOf<String, List<Cookie>>()
    private val prefs = context.getSharedPreferences("cookie_store", Context.MODE_PRIVATE)

    private val lock = ReentrantLock()

    // Komu (ADR-0087 Review 1, recheck 1): a generation per cookie identity, bumped
    // on every write or removal under [lock], so the WebView bridge can apply a
    // session's change only when the record is unchanged since the session saw it.
    private val generations = HashMap<Identity, Long>()
    private var epoch = 0L

    /** RFC 6265 §5.3 identity — used by every write, the restore and the WebView diff. */
    data class Identity(
        val name: String,
        val domain: String,
        val path: String,
    )

    private val Cookie.identity: Identity get() = Identity(name, domain, path)

    /** Replaces the cookie with [cookie]'s identity (or adds it); caller holds [lock]. */
    private fun storeLocked(cookie: Cookie) {
        val cookiesForDomain = cookieMap[cookie.domain].orEmpty().toMutableList()
        val pos = cookiesForDomain.indexOfFirst { it.identity == cookie.identity }
        if (pos == -1) cookiesForDomain.add(cookie) else cookiesForDomain[pos] = cookie
        cookieMap[cookie.domain] = cookiesForDomain
        generations[cookie.identity] = ++epoch
    }

    /** Removes the cookie with [identity]; whether it was there. Caller holds [lock]. */
    private fun deleteLocked(identity: Identity): Boolean {
        val cookies = cookieMap[identity.domain] ?: return false
        val kept = cookies.filterNot { it.identity == identity }
        if (kept.size == cookies.size) return false
        cookieMap[identity.domain] = kept
        generations[identity] = ++epoch
        return true
    }

    init {
        lock.withLock {
            val domains =
                prefs.all.keys
                    .map { it.substringBeforeLast(".") }
                    .toSet()
            val domainsToSave = mutableSetOf<String>()
            domains.forEach { domain ->
                val cookies = prefs.getStringSet(domain, emptySet())
                if (!cookies.isNullOrEmpty()) {
                    try {
                        val url = "http://$domain".toHttpUrlOrNull() ?: return@forEach
                        val nonExpiredCookies =
                            cookies
                                .mapNotNull { Cookie.parse(url, it) }
                                .filter { !it.hasExpired() }
                                .groupBy { it.domain }
                                .mapValues { it.value.distinctBy { cookie -> cookie.identity } }
                        nonExpiredCookies.forEach { (domain, cookies) ->
                            cookieMap[domain] = cookies
                            domainsToSave.add(domain)
                        }
                        domainsToSave.add(domain)
                    } catch (_: Exception) {
                        // Ignore
                    }
                }
            }
            saveToDisk(domainsToSave)
        }
    }

    fun addAll(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        lock.withLock {
            val domainsToSave = mutableSetOf<String>()
            // Append or replace the cookies for this domain.
            for (cookie in cookies) {
                // Komu: replace the cookie with the same identity (RFC 6265 §5.3: name, domain, path).
                storeLocked(cookie)
                domainsToSave.add(cookie.domain)
            }

            saveToDisk(domainsToSave.toSet())
        }
    }

    override fun removeAll(): Boolean =
        lock.withLock {
            val wasNotEmpty = cookieMap.isEmpty()
            prefs.edit().clear().apply()
            cookieMap.values.flatten().forEach { generations[it.identity] = ++epoch }
            cookieMap.clear()
            wasNotEmpty
        }

    fun remove(uri: URI) {
        val url = uri.toURL()
        lock.withLock {
            prefs.edit().remove(url.host).apply()
            cookieMap.remove(url.host)?.forEach { generations[it.identity] = ++epoch }
        }
    }

    override fun get(uri: URI): List<HttpCookie> {
        val url = uri.toURL()
        return get(url.toHttpUrlOrNull()!!).map {
            it.toHttpCookie()
        }
    }

    // Komu (ADR-0087 Review 1): RFC 6265 §5.4 — a host-only cookie goes to its own
    // host only, a domain cookie to its domain and subdomains (never to a host that
    // merely ends with the same letters), on a matching path, Secure over HTTPS only,
    // and never after it expired.
    fun get(url: HttpUrl): List<Cookie> =
        lock.withLock {
            val now = System.currentTimeMillis()
            cookieMap.values.flatMap { cookies -> cookies.filter { it.expiresAt > now && it.matches(url) } }
        }

    /** Komu: every stored, unexpired cookie (the WebView cookie bridge). */
    fun all(): List<Cookie> =
        lock.withLock {
            val now = System.currentTimeMillis()
            cookieMap.values.flatMap { cookies -> cookies.filter { it.expiresAt > now } }
        }

    /** Komu: stores [cookie], replacing the one with the same identity (name, domain, path). */
    fun put(cookie: Cookie) {
        lock.withLock {
            storeLocked(cookie)
            saveToDisk(setOf(cookie.domain))
        }
    }

    /** Komu: removes the cookie with this identity. */
    fun remove(
        name: String,
        domain: String,
        path: String,
    ) {
        lock.withLock {
            if (deleteLocked(Identity(name, domain, path))) saveToDisk(setOf(domain))
        }
    }

    /** Komu: the identity's current generation (0: never written) and the store's latest. */
    fun generation(identity: Identity): Long = lock.withLock { generations[identity] ?: 0L }

    fun epoch(): Long = lock.withLock { epoch }

    /**
     * Komu (ADR-0087 Recheck 3): the identity's unexpired cookie (null: absent) and its
     * generation, read together under [lock]. A removal keeps the identity's generation
     * (a tombstone), so absence is versioned like a record.
     */
    fun read(identity: Identity): Pair<Cookie?, Long> = lock.withLock { readLocked(identity) }

    private fun readLocked(identity: Identity): Pair<Cookie?, Long> {
        val now = System.currentTimeMillis()
        val cookie = cookieMap[identity.domain]?.firstOrNull { it.identity == identity && it.expiresAt > now }
        return cookie to (generations[identity] ?: 0L)
    }

    /**
     * Komu: atomically sets (or, for null, removes) the cookie with [identity], only
     * when the identity is still in the state the caller read — the same generation,
     * and present or absent as it was ([expectedPresent]). Read, compare and write
     * happen under one hold of [lock]. Returns the new generation, or null when the
     * state changed.
     */
    fun compareAndSet(
        identity: Identity,
        expectedPresent: Boolean,
        expected: Long,
        cookie: Cookie?,
    ): Long? =
        lock.withLock {
            val (current, generation) = readLocked(identity)
            if (generation != expected || (current != null) != expectedPresent) return@withLock null
            if (cookie == null) {
                if (deleteLocked(identity)) saveToDisk(setOf(identity.domain)) else generations[identity] = ++epoch
            } else {
                storeLocked(cookie)
                saveToDisk(setOf(cookie.domain))
            }
            generations[identity]
        }

    /** Komu: every stored, unexpired cookie with its generation, read atomically. */
    fun allVersioned(): List<Pair<Cookie, Long>> =
        lock.withLock {
            val now = System.currentTimeMillis()
            cookieMap.values.flatMap { cookies ->
                cookies.filter { it.expiresAt > now }.map { it to (generations[it.identity] ?: 0L) }
            }
        }

    override fun add(
        uri: URI?,
        cookie: HttpCookie,
    ) {
        lock.withLock {
            val cookie = cookie.toCookie(uri?.host) ?: return@withLock
            // Komu: replace the cookie with the same identity (RFC 6265 §5.3: name, domain, path).
            storeLocked(cookie)
            saveToDisk(setOf(cookie.domain))
        }
    }

    override fun getCookies(): List<HttpCookie> =
        lock.withLock {
            cookieMap.values.flatMap {
                it.map {
                    it.toHttpCookie()
                }
            }
        }

    fun getStoredCookies(): List<Cookie> =
        lock.withLock {
            cookieMap.values.flatMap { it }
        }

    override fun getURIs(): List<URI> =
        lock.withLock {
            cookieMap.keys.toList().map {
                URI("http://$it")
            }
        }

    override fun remove(
        uri: URI?,
        cookie: HttpCookie,
    ): Boolean =
        lock.withLock {
            val cookie = cookie.toCookie(uri?.host) ?: return@withLock false
            if (deleteLocked(cookie.identity)) {
                saveToDisk(setOf(cookie.domain))
                true
            } else {
                false
            }
        }

    private fun saveToDisk(domains: Set<String>) {
        // Get cookies to be stored in disk
        prefs
            .edit()
            .apply {
                domains.forEach { domain ->
                    val newValues =
                        cookieMap[domain]
                            .orEmpty()
                            .asSequence()
                            .filter { it.persistent && !it.hasExpired() }
                            .map(Cookie::toString)
                            .toSet()
                    if (newValues.isNotEmpty()) {
                        remove(domain)
                        putStringSet(domain, newValues)
                    } else {
                        remove(domain)
                    }
                }
            }.apply()
    }

    private fun Cookie.hasExpired() = System.currentTimeMillis() >= expiresAt

    private fun HttpCookie.toCookie(urlDomain: String?): Cookie? {
        return Cookie
            .Builder()
            .name(name)
            .value(value)
            .domain((domain ?: urlDomain ?: return null).removePrefix("."))
            .path(path ?: "/")
            .also {
                if (maxAge != -1L) {
                    it.expiresAt(System.currentTimeMillis() + maxAge.seconds.inWholeMilliseconds)
                } else {
                    it.expiresAt(Long.MAX_VALUE)
                }
                if (secure) {
                    it.secure()
                }
                if (isHttpOnly) {
                    it.httpOnly()
                }
                // Komu: without a Domain the cookie is host-only (RFC 6265 §5.3 step 6),
                // as java.net.CookieManager and Android's CookieManager store it.
                if (domain == null || !domain.startsWith('.')) {
                    it.hostOnlyDomain((domain ?: urlDomain!!).removePrefix("."))
                }
            }.build()
    }

    private fun Cookie.toHttpCookie(): HttpCookie {
        val it = this
        return HttpCookie(it.name, it.value).apply {
            domain =
                if (hostOnly) {
                    it.domain
                } else {
                    "." + it.domain
                }
            path = it.path
            secure = it.secure
            maxAge =
                if (it.persistent) {
                    -1
                } else {
                    (it.expiresAt.milliseconds - System.currentTimeMillis().milliseconds).inWholeSeconds
                }

            isHttpOnly = it.httpOnly
        }
    }
}
