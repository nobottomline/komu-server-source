package xyz.nulldev.androidcompat.webkit.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0087 Review 1: the WebView cookie bridge keeps RFC 6265 semantics between the
 * server's jar and a session's store. Each scenario runs against the current model
 * and, as a red control, against [Legacy] — the transfer as 2.4.2457 shipped it
 * (`Domain` always written, only additions and changes exported, filtered by the
 * hosts known before the event) — which must violate it.
 */
class WebCookiesTest {
    private var now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun cookie(
        name: String,
        domain: String,
        hostOnly: Boolean = true,
        value: String = "v",
        path: String = "/",
        secure: Boolean = false,
        httpOnly: Boolean = false,
        sameSite: String? = null,
        expiresAt: Long? = null,
    ) = WebCookie.canonical(name, value, domain, hostOnly, path, secure, httpOnly, sameSite, expiresAt)

    /** The engine's view of what a preload put into the session's store. */
    private fun roundTrip(cookies: List<WebCookie>): List<WebCookie> = CookieWire.decode(CookieWire.encode(cookies))

    /** 2.4.2457's transfer, modelled for the red controls. */
    private class Legacy(
        private val jar: MemoryWebViewCookieJar,
    ) {
        val targetHosts = LinkedHashSet<String>()
        private val exported = HashMap<WebCookie.Key, String>()

        /** Preload: the cookies for the URL, every one written with `Domain` (a domain cookie in the engine). */
        fun preload(host: String): List<WebCookie> {
            targetHosts += host
            return jar.cookiesFor(host).map { it.cookie.copy(hostOnly = false) }.onEach { exported[it.key] = it.value }
        }

        /** Export: changed cookies only, kept when a host known before this event would receive them. */
        fun export(
            hostsAfterTheEvent: List<String>,
            snapshot: List<WebCookie>,
        ) {
            for (c in snapshot) {
                if (exported[c.key] == c.value) continue
                exported[c.key] = c.value
                if (targetHosts.any { RfcCookies.domainMatches(it, c.domain) }) jar.put(c)
            }
            targetHosts += hostsAfterTheEvent
        }
    }

    @Test
    fun hostOnlyCookiesStayHostOnlyAndNeverReachSubdomains() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("sid", "site.test", hostOnly = true))
        jar.put(cookie("pref", "site.test", hostOnly = false))
        val sync = CookieSync({ jar }, { now })

        val forSite = roundTrip(sync.preload("site.test").second.set).associateBy { it.name }
        assertEquals(true, forSite["sid"]?.hostOnly, "host-only: no Domain attribute added")
        assertEquals(false, forSite["pref"]?.hostOnly)
        assertFalse(forSite.getValue("sid").appliesTo("ads.site.test"), "the engine would not send it to a subdomain")

        val forSubdomain = roundTrip(CookieSync({ jar }, { now }).preload("ads.site.test").second.set).map { it.name }
        assertEquals(listOf("pref"), forSubdomain, "a host-only cookie is not copied for a subdomain; a domain cookie is")
        assertEquals(emptyList(), CookieSync({ jar }, { now }).preload("evilsite.test").second.set, "a mere suffix is not a subdomain")

        // Red control: 2.4.2457 wrote Domain for every cookie.
        val legacy = Legacy(jar).preload("site.test").single { it.name == "sid" }
        assertTrue(legacy.appliesTo("ads.site.test"), "red control: the legacy transfer widened sid to ads.site.test")
    }

    @Test
    fun domainMatchingFollowsRfc6265() {
        assertTrue(RfcCookies.domainMatches("www.site.test", "site.test"))
        assertTrue(RfcCookies.domainMatches("site.test", "site.test"))
        assertFalse(RfcCookies.domainMatches("site.test", "www.site.test"))
        assertFalse(RfcCookies.domainMatches("evilsite.test", "site.test"))
        assertFalse(RfcCookies.domainMatches("127.0.0.1", "0.0.1"), "an IP address never domain-matches a suffix")
        assertTrue(cookie("a", "127.0.0.1", hostOnly = false).appliesTo("127.0.0.1"))
    }

    @Test
    fun everyAttributeRoundTrips() {
        val cookies =
            listOf(
                cookie("h", "site.test", hostOnly = true, path = "/p", secure = true, httpOnly = true, sameSite = "strict", expiresAt = now + day + 123),
                cookie("d", ".Site.Test", hostOnly = false, sameSite = "Lax"),
                cookie("n", "site.test", sameSite = "None", value = "a=b; c"),
                cookie("s", "site.test"),
            )
        val back = roundTrip(cookies)
        assertEquals(cookies, back)
        assertEquals("site.test", back[1].domain, "canonical domain: lower case, no dot")
        assertEquals(now + day, back[0].expiresAt, "expiry in whole seconds")
        assertEquals("Strict", back[0].sameSite)
        assertNull(back[3].expiresAt, "a session cookie stays one")
    }

    @Test
    fun aCookieThePageDeletesLeavesTheJar() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("gone", "site.test"))
        jar.put(cookie("kept", "site.test"))
        val sync = CookieSync({ jar }, { now })
        val preloaded = sync.preload("site.test").second.set
        sync.reconcile(listOf("site.test"), preloaded) // the engine's snapshot after the preload: both hold them
        sync.reconcile(listOf("site.test"), preloaded.filter { it.name != "gone" })
        assertNull(jar.find(cookie("gone", "site.test").key), "deleted by the page: deleted in the jar")
        assertNotNull(jar.find(cookie("kept", "site.test").key))

        // Red control: the legacy export never deletes.
        val legacyJar = MemoryWebViewCookieJar { now }.apply { put(cookie("gone", "site.test")) }
        val legacy = Legacy(legacyJar)
        val legacyPreloaded = legacy.preload("site.test")
        legacy.export(listOf("site.test"), legacyPreloaded.filter { it.name != "gone" })
        assertNotNull(legacyJar.find(cookie("gone", "site.test").key), "red control: the legacy jar resurrects it")
    }

    @Test
    fun aCookieThatExpiredInThePageLeavesTheJar() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("short", "site.test", expiresAt = now + day))
        val sync = CookieSync({ jar }, { now })
        sync.reconcile(listOf("site.test"), sync.preload("site.test").second.set)
        // The page set it again with an Expires in the past (the engine may still list it).
        sync.reconcile(listOf("site.test"), listOf(cookie("short", "site.test", expiresAt = now - 1000)))
        assertNull(jar.find(cookie("short", "site.test").key))

        // Red control: the legacy export writes the expired cookie back (the jar then
        // drops it only once read; the old jar never filtered expiry on reads).
        val legacyJar = MemoryWebViewCookieJar { now - 2 * day }.apply { put(cookie("short", "site.test", expiresAt = now + day)) }
        val legacy = Legacy(legacyJar)
        legacy.preload("site.test")
        legacy.export(listOf("site.test"), listOf(cookie("short", "site.test", value = "x", expiresAt = now - 1000)))
        assertNotNull(legacyJar.all().firstOrNull { it.name == "short" }, "red control: still in the legacy jar")
    }

    @Test
    fun aNewerServerValueSurvivesThePagesDeletionOfTheOldOne() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("token", "site.test", value = "old"))
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test").second
        jar.put(cookie("token", "site.test", value = "new")) // a server response meanwhile
        sync.reconcile(listOf("site.test"), emptyList())
        assertEquals("new", jar.find(cookie("token", "site.test").key)?.value)
    }

    @Test
    fun aRedirectChainsCookiesReachTheJarBecauseItsHostsAreAdmittedFirst() {
        val jar = MemoryWebViewCookieJar { now }
        val sync = CookieSync({ jar }, { now })
        sync.preload("a.test").second
        // a.test → 302 b.test → 302 c.test: each hop admitted before its response set a cookie.
        val snapshot = listOf(cookie("fromB", "b.test"), cookie("fromC", "c.test", hostOnly = false))
        sync.reconcile(listOf("a.test", "b.test", "c.test"), snapshot)
        assertNotNull(jar.find(cookie("fromB", "b.test").key))
        assertNotNull(jar.find(cookie("fromC", "c.test").key))
        // A later snapshot without a change writes nothing again and deletes nothing.
        sync.reconcile(listOf("a.test", "b.test", "c.test"), snapshot)
        assertEquals(2, jar.all().size)

        // Red control: the legacy export ran before the hop's PAGE_STARTED added it.
        val legacyJar = MemoryWebViewCookieJar { now }
        val legacy = Legacy(legacyJar)
        legacy.preload("a.test")
        legacy.export(listOf("b.test", "c.test"), snapshot)
        legacy.export(emptyList(), snapshot)
        assertEquals(emptyList(), legacyJar.all(), "red control: the redirect target's cookies are lost")
    }

    @Test
    fun thirdPartyFramesNeverWriteOrDeleteJarCookies() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("other", "third.test"))
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test").second
        val snapshot =
            listOf(
                cookie("tracker", "third.test"), // a third-party frame's own cookie
                cookie("frame", "ads.site.test"), // a same-site frame's host-only cookie: not sent to site.test
                cookie("shared", "site.test", hostOnly = false), // Domain=site.test from a same-site frame: sent to site.test
            )
        sync.reconcile(listOf("site.test"), snapshot)
        assertEquals(setOf("other", "shared"), jar.all().map { it.name }.toSet())
        assertEquals("v", jar.find(cookie("other", "third.test").key)?.value, "a cookie outside the targets is never deleted either")
        assertEquals(setOf("site.test"), sync.targetHosts(), "sub-frame hosts never become targets")

        // Red control: the legacy filter keeps a cookie whose domain merely matches a target host the other way round.
        val legacyJar = MemoryWebViewCookieJar { now }
        val legacy = Legacy(legacyJar)
        legacy.targetHosts += "ads.site.test" // the legacy preload of a subdomain load
        legacy.export(emptyList(), listOf(cookie("wide", "site.test", hostOnly = true)))
        assertTrue(legacyJar.all().any { it.name == "wide" }, "red control: legacy kept a host-only cookie of another host")
        assertFalse(cookie("wide", "site.test", hostOnly = true).appliesTo("ads.site.test"))
    }

    @Test
    fun sameSiteNoneAndUnspecifiedAreTheSameToTheEngine() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("cf", "site.test", sameSite = "None"))
        val sync = CookieSync({ jar }, { now })
        val preloaded = sync.preload("site.test").second.set
        // WebKit stores only Lax and Strict: it reports None as unspecified.
        sync.reconcile(listOf("site.test"), preloaded.map { it.copy(sameSite = null) })
        assertEquals("None", jar.find(cookie("cf", "site.test").key)?.sameSite, "no churn, the jar's attribute kept")
    }

    @Test
    fun withoutAJarNothingIsExchanged() {
        val sync = CookieSync({ null }, { now })
        assertEquals(CookiePush.NONE, sync.preload("site.test").second)
        sync.reconcile(listOf("site.test"), listOf(cookie("a", "site.test")))
    }

    // ---------------------------------------------------------------- Codex recheck 1: ordering

    @Test
    fun aReportOlderThanThePreloadDeletesNothing() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("auth", "site.test"))
        val sync = CookieSync({ jar }, { now })
        val (sequence, _) = sync.preload("site.test")
        // The engine read the store before setCookie finished: an empty report, preload not yet in.
        sync.reconcile(listOf("site.test"), emptyList(), preloadsDone = sequence - 1)
        assertNotNull(jar.find(cookie("auth", "site.test").key), "the server cookie survives")
        sync.reconcile(listOf("site.test"), listOf(cookie("auth", "site.test")), preloadsDone = sequence)
        assertNotNull(jar.find(cookie("auth", "site.test").key))
        // Recheck 2: the store never confirmed holding it before the empty report, so no
        // report order can delete it (the base had not reached it).
    }

    @Test
    fun aStaleReportNeverOverwritesARotatedToken() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("token", "site.test", value = "v1"))
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test")
        jar.put(cookie("token", "site.test", value = "v3")) // a REST response rotates it
        jar.put(cookie("fresh", "site.test", value = "server")) // and sets a new one
        // The page's report, taken before: it changed token and set fresh itself.
        sync.reconcile(listOf("site.test"), listOf(cookie("token", "site.test", value = "v2"), cookie("fresh", "site.test", value = "page")))
        assertEquals("v3", jar.find(cookie("token", "site.test").key)?.value, "the rotated token wins")
        assertEquals("server", jar.find(cookie("fresh", "site.test").key)?.value)
        // Recheck 2: once the store holds the server's v3 (pushed back), a later change by the page applies.
        sync.reconcile(listOf("site.test"), listOf(cookie("token", "site.test", value = "v3"), cookie("fresh", "site.test", value = "server")))
        sync.reconcile(listOf("site.test"), listOf(cookie("token", "site.test", value = "v4"), cookie("fresh", "site.test", value = "server")))
        assertEquals("v4", jar.find(cookie("token", "site.test").key)?.value)

        // Red control: an unconditional write (the Review 1 model) puts v2 back over v3.
        val red = MemoryWebViewCookieJar { now }.apply { put(cookie("token", "site.test", value = "v3")) }
        red.put(cookie("token", "site.test", value = "v2"))
        assertEquals("v2", red.find(cookie("token", "site.test").key)?.value, "red control: the old value overwrote the rotated one")
    }

    @Test
    fun aDeletionDoesNotRemoveARecordRewrittenWithTheSameValue() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("sid", "site.test", value = "same", expiresAt = now + day))
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test")
        jar.put(cookie("sid", "site.test", value = "same", expiresAt = now + 30 * day)) // renewed: same value, new expiry
        sync.reconcile(listOf("site.test"), emptyList())
        assertEquals(now + 30 * day, jar.find(cookie("sid", "site.test").key)?.expiresAt, "the renewed record stays")

        // Red control: the Review 1 rule compared values only and removed it.
        val red = MemoryWebViewCookieJar { now }.apply { put(cookie("sid", "site.test", value = "same", expiresAt = now + 30 * day)) }
        if (red.find(cookie("sid", "site.test").key)?.value == "same") red.compareAndSet(cookie("sid", "site.test").key, red.generation(cookie("sid", "site.test").key), null)
        assertNull(red.find(cookie("sid", "site.test").key), "red control: value-only comparison deletes the renewed record")
    }
}
