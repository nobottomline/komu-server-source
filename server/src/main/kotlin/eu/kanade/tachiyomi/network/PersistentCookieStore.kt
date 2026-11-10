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
                                .mapValues { it.value.distinctBy { it.name } }
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
                val cookiesForDomain = cookieMap[cookie.domain].orEmpty().toMutableList()
                // Komu: replace the cookie with the same identity (RFC 6265 §5.3: name, domain, path).
                val pos = cookiesForDomain.indexOfFirst { it.name == cookie.name && it.path == cookie.path }
                if (pos == -1) {
                    cookiesForDomain.add(cookie)
                } else {
                    cookiesForDomain[pos] = cookie
                }
                cookieMap[cookie.domain] = cookiesForDomain
                domainsToSave.add(cookie.domain)
            }

            saveToDisk(domainsToSave.toSet())
        }
    }

    override fun removeAll(): Boolean =
        lock.withLock {
            val wasNotEmpty = cookieMap.isEmpty()
            prefs.edit().clear().apply()
            cookieMap.clear()
            wasNotEmpty
        }

    fun remove(uri: URI) {
        val url = uri.toURL()
        lock.withLock {
            prefs.edit().remove(url.host).apply()
            cookieMap.remove(url.host)
        }
    }

    override fun get(uri: URI): List<HttpCookie> {
        val url = uri.toURL()
        return get(url.toHttpUrlOrNull()!!).map {
            it.toHttpCookie()
        }
    }

    // Komu (ADR-0086 Review 1): RFC 6265 §5.4 — a host-only cookie goes to its own
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
            val cookiesForDomain = cookieMap[cookie.domain].orEmpty().toMutableList()
            val pos = cookiesForDomain.indexOfFirst { it.name == cookie.name && it.path == cookie.path }
            if (pos == -1) cookiesForDomain.add(cookie) else cookiesForDomain[pos] = cookie
            cookieMap[cookie.domain] = cookiesForDomain
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
            val cookies = cookieMap[domain] ?: return@withLock
            val kept = cookies.filterNot { it.name == name && it.path == path }
            if (kept.size == cookies.size) return@withLock
            cookieMap[domain] = kept
            saveToDisk(setOf(domain))
        }
    }

    override fun add(
        uri: URI?,
        cookie: HttpCookie,
    ) {
        lock.withLock {
            val cookie = cookie.toCookie(uri?.host) ?: return@withLock
            val cookiesForDomain = cookieMap[cookie.domain].orEmpty().toMutableList()
            // Komu: replace the cookie with the same identity (RFC 6265 §5.3: name, domain, path).
            val pos = cookiesForDomain.indexOfFirst { it.name == cookie.name && it.path == cookie.path }
            if (pos == -1) {
                cookiesForDomain.add(cookie)
            } else {
                cookiesForDomain[pos] = cookie
            }
            cookieMap[cookie.domain] = cookiesForDomain
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
            val cookies = cookieMap[cookie.domain].orEmpty()
            val index =
                cookies.indexOfFirst {
                    it.name == cookie.name &&
                        it.path == cookie.path
                }
            if (index >= 0) {
                val newList = cookies.toMutableList()
                newList.removeAt(index)
                cookieMap[cookie.domain] = newList.toList()
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
