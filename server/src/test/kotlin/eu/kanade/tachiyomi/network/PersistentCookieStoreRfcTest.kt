package eu.kanade.tachiyomi.network

import eu.kanade.tachiyomi.network.KomuWebViewCookieJar.Companion.toOkHttp
import eu.kanade.tachiyomi.network.KomuWebViewCookieJar.Companion.toWebCookie
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.webkit.CookieManagerImpl
import xyz.nulldev.androidcompat.webkit.ios.WebCookie
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0087 Review 1: the server jar the WebView bridge reconciles into keeps RFC 6265
 * semantics — host-only stays host-only (also for OkHttp's own requests), identity is
 * (name, domain, path), expired cookies are never sent, and the WebView side sees
 * every attribute.
 */
class PersistentCookieStoreRfcTest : ApplicationTest() {
    /** The server's own jar (OkHttp's and Android's CookieManager's). */
    private val store by lazy { Injekt.get<NetworkHelper>().cookieStore }
    private val tag = "rfc${System.nanoTime()}"
    private val site = "$tag.test"

    @AfterTest
    fun clean() {
        store.all().filter { it.domain.endsWith(site) }.forEach { store.remove(it.name, it.domain, it.path) }
    }

    private fun names(url: String) = store.get(url.toHttpUrl()).map { it.name }.sorted()

    @Test
    fun hostOnlyCookiesAreSentToTheirHostOnlyAndDomainCookiesToSubdomains() {
        val url = "https://$site/".toHttpUrl()
        store.addAll(url, listOf(Cookie.parse(url, "host=1; Path=/")!!, Cookie.parse(url, "dom=1; Domain=$site; Path=/")!!))
        assertEquals(listOf("dom", "host"), names("https://$site/"))
        assertEquals(listOf("dom"), names("https://ads.$site/"), "red before Review 1: host was sent to the subdomain")
        assertEquals(emptyList(), names("https://evil$site/"), "red before Review 1: a mere suffix matched")
    }

    @Test
    fun pathSecureAndExpiryApplyToRequests() {
        val url = "https://$site/".toHttpUrl()
        store.addAll(
            url,
            listOf(
                Cookie.parse(url, "p=1; Path=/api")!!,
                Cookie.parse(url, "s=1; Path=/; Secure")!!,
            ),
        )
        store.put(
            Cookie
                .Builder()
                .name("old")
                .value("1")
                .hostOnlyDomain(site)
                .expiresAt(System.currentTimeMillis() - 1_000)
                .build(),
        )
        assertEquals(listOf("p", "s"), names("https://$site/api/x"))
        assertEquals(listOf("s"), names("https://$site/"))
        assertEquals(emptyList(), names("http://$site/"), "Secure never over plain HTTP")
    }

    @Test
    fun identityIsNameDomainAndPath() {
        val url = "https://$site/".toHttpUrl()
        store.addAll(url, listOf(Cookie.parse(url, "a=1; Path=/x")!!, Cookie.parse(url, "a=2; Path=/y")!!))
        assertEquals(2, store.all().count { it.domain == site && it.name == "a" })
        store.addAll(url, listOf(Cookie.parse(url, "a=3; Path=/x")!!))
        assertEquals(setOf("/x" to "3", "/y" to "2"), store.all().filter { it.domain == site }.map { it.path to it.value }.toSet())
        store.remove("a", site, "/x")
        assertEquals(listOf("/y"), store.all().filter { it.domain == site }.map { it.path })
    }

    @Test
    fun theWebViewJarRoundTripsEveryAttribute() {
        val jar = KomuWebViewCookieJar(store)
        val expiry = (System.currentTimeMillis() / 1000 + 3600) * 1000
        val cookies =
            listOf(
                WebCookie.canonical("h", "1", site, true, "/p", true, true, "Strict", expiry),
                WebCookie.canonical("d", "2", site, false, "/", false, false, "Lax", null),
            )
        cookies.forEach { assertNotNull(jar.compareAndSet(it.key, jar.read(it.key), it)) }
        assertEquals(cookies.toSet(), jar.cookiesFor(site).map { it.cookie }.toSet())
        assertEquals(listOf("d"), jar.cookiesFor("ads.$site").map { it.cookie.name })
        val state = jar.read(cookies[0].key)
        store.put(cookies[0].toOkHttp()!!) // a server write meanwhile
        assertNull(jar.compareAndSet(cookies[0].key, state, null), "a changed record is not removed")
        assertNotNull(jar.compareAndSet(cookies[0].key, jar.read(cookies[0].key), null))
        assertTrue(jar.cookiesFor(site).none { it.cookie.name == "h" })
        cookies.forEach { assertEquals(it, it.toOkHttp()!!.toWebCookie()) }
    }

    /**
     * ADR-0087 Recheck 3: an identity's record or absence and its generation are read
     * together, removal leaves a tombstone generation, and the compare-and-set expects
     * the whole state (absent at g is neither absent at g + 1 nor present at g).
     */
    @Test
    fun absenceIsAVersionedStateTheCompareAndSetExpects() {
        val jar = KomuWebViewCookieJar(store)
        val v1 = WebCookie.canonical("t", "v1", site, true, "/", true, true, "Strict", null)
        assertEquals(0L, jar.read(v1.key).generation, "never written: absent at 0")
        assertNotNull(jar.compareAndSet(v1.key, jar.read(v1.key), v1))
        store.remove("t", site, "/")
        val absent = jar.read(v1.key)
        assertNull(absent.cookie)
        assertTrue(absent.generation > 0, "a removal leaves a tombstone generation")
        store.put(v1.copy(value = "v3").toOkHttp()!!)
        assertNull(jar.compareAndSet(v1.key, absent, v1.copy(value = "v2")), "a REST write after the read wins")
        assertEquals("v3", jar.read(v1.key).cookie?.value)
        store.remove("t", site, "/")
        assertNull(jar.compareAndSet(v1.key, absent, v1.copy(value = "v2")), "absent then is not absent now")
        assertNull(jar.compareAndSet(v1.key, jar.read(v1.key).copy(cookie = v1), null), "absent is not present at the same generation")
        assertNotNull(jar.compareAndSet(v1.key, jar.read(v1.key), v1.copy(value = "v2")))
        assertEquals("v2", jar.read(v1.key).cookie?.value)
    }

    @Test
    fun androidsCookieManagerStoresHostOnlyWithoutDomainAndDomainCookiesWithIt() {
        val manager = CookieManagerImpl()
        manager.setCookie("https://$site/", "plain=1; Path=/")
        manager.setCookie("https://$site/", "wide=1; Domain=$site; Path=/")
        val stored = store.all().filter { it.domain == site }.associateBy { it.name }
        assertEquals(true, stored["plain"]?.hostOnly, "red before Review 1: stored as a domain cookie")
        assertEquals(false, stored["wide"]?.hostOnly, "red before Review 1: stored as host-only")
        assertTrue("plain=1" !in manager.getCookie("https://ads.$site/"))
        assertTrue("wide=1" in manager.getCookie("https://ads.$site/"))
    }

    /** Codex recheck 1: a restart keeps both cookies of the same name on two paths. */
    @Test
    fun aRestartKeepsSameNamedCookiesOnTwoPaths() {
        val url = "https://$site/".toHttpUrl()
        store.addAll(
            url,
            listOf(
                Cookie.parse(url, "a=root; Path=/; Max-Age=3600")!!,
                Cookie.parse(url, "a=api; Path=/api; Max-Age=3600")!!,
            ),
        )
        val restarted = PersistentCookieStore(Injekt.get<android.app.Application>())
        assertEquals(setOf("/" to "root", "/api" to "api"), restarted.all().filter { it.domain == site }.map { it.path to it.value }.toSet(), "red before recheck 1: distinctBy(name) dropped one")
    }
}
