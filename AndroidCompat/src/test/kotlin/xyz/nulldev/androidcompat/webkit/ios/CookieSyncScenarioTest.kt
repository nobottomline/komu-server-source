package xyz.nulldev.androidcompat.webkit.ios

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Komu ADR-0087 Recheck 2 and 3: Codex's cookie scenarios through [CookieSync]. The
 * Recheck 2 scenario ran against the Recheck 1 model as its red control; the Recheck 3
 * interleavings against the split read (record from the host snapshot, generation
 * from a later call; `recheck3/red/`).
 */
class CookieSyncScenarioTest {
    private val now = 1_800_000_000_000L

    private fun cookie(value: String) = WebCookie.canonical("token", value, "site.test", true, "/", false, false, null, null)

    /**
     * Codex recheck 2 (P2): preload v1, a REST response writes v3, the page's snapshot
     * shows v2 — the write is refused — and a later snapshot without the cookie
     * deleted v3 (the base had taken the server's generation although the store never
     * held v3).
     */
    @Test
    fun aRefusedWriteNeverLetsALaterSnapshotDeleteTheServersRecord() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("v1"))
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test")
        sync.reconcile(listOf("site.test"), listOf(cookie("v1")), 1) // the store holds the preload
        jar.put(cookie("v3")) // a REST response rotates the token
        sync.reconcile(listOf("site.test"), listOf(cookie("v2")), 1) // the page's change: refused
        assertEquals("v3", jar.find(cookie("x").key)?.value, "the server's value wins the conflict")
        sync.reconcile(listOf("site.test"), emptyList(), 1) // the page drops the cookie
        assertEquals("v3", jar.find(cookie("x").key)?.value, "the store never held v3: its absence deletes nothing")
    }

    /**
     * A jar that runs [hook] once at a chosen point of a reconciliation: after its host
     * snapshot ([cookiesFor]) or after a per-identity [read], before the next call.
     */
    private class InterleavingJar(
        val inner: MemoryWebViewCookieJar,
        private val afterSnapshot: Boolean,
    ) : WebViewCookieJar by inner {
        var hook: (() -> Unit)? = null

        private fun fire() {
            hook?.also { hook = null }?.invoke()
        }

        override fun cookiesFor(host: String): List<VersionedCookie> = inner.cookiesFor(host).also { if (afterSnapshot) fire() }

        override fun read(key: WebCookie.Key): JarState = inner.read(key).also { if (!afterSnapshot) fire() }
    }

    private fun secured(value: String) = WebCookie.canonical("token", value, "site.test", true, "/", true, true, "Strict", null)

    /**
     * Codex recheck 3 (P2): base absent, the server's snapshot empty, the store holds
     * v2; a REST response writes v3 (Secure, HttpOnly) after the snapshot was read and
     * before the compare-and-set. The split read took the record from the snapshot
     * (absent) and the generation from a later call (v3's), so the CAS of "absent"
     * replaced v3 with v2. The state is now read in one call and the CAS expects it.
     */
    private fun restWriteLandsBetweenTheReadAndTheCAS(afterSnapshot: Boolean) {
        val jar = InterleavingJar(MemoryWebViewCookieJar { now }, afterSnapshot)
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test") // nothing in the jar: base stays absent
        jar.hook = { jar.inner.put(secured("v3")) }
        val push = sync.reconcile(listOf("site.test"), listOf(cookie("v2")), 1)
        assertEquals(null, jar.hook, "the REST write landed during the reconciliation")
        val kept = jar.inner.find(cookie("x").key)
        assertEquals("v3", kept?.value, "the server's record is kept")
        assertEquals(true, kept?.secure, "with its Secure attribute")
        assertEquals(true, kept?.httpOnly, "and its HttpOnly attribute")
        assertEquals(listOf(secured("v3")), push.set, "the server's record goes back into the store")
        assertEquals(null, sync.baseFor(cookie("x").key), "the base does not move to a record the store never held")
    }

    @Test
    fun aRESTWriteAfterTheSnapshotWinsOverThePagesRecord() = restWriteLandsBetweenTheReadAndTheCAS(afterSnapshot = true)

    @Test
    fun aRESTWriteAfterTheIdentitysReadWinsOverThePagesRecord() = restWriteLandsBetweenTheReadAndTheCAS(afterSnapshot = false)

    /** Positive control: with no write in between, the page's record reaches the jar. */
    @Test
    fun withoutAnInterleavedWriteThePagesRecordReachesTheJar() {
        val jar = InterleavingJar(MemoryWebViewCookieJar { now }, afterSnapshot = true)
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test")
        sync.reconcile(listOf("site.test"), listOf(cookie("v2")), 1)
        assertEquals("v2", jar.inner.find(cookie("x").key)?.value)
    }

    /** Absence is versioned: "absent at g" is not "absent at g + 1" (removed, rewritten and removed again). */
    @Test
    fun aTombstoneGenerationRefusesAStaleAbsentExpectation() {
        val jar = MemoryWebViewCookieJar { now }
        val key = cookie("x").key
        jar.put(cookie("v1"))
        jar.remove(key)
        val absent = jar.read(key)
        assertEquals(null, absent.cookie)
        assertEquals(true, absent.generation > 0, "a removal leaves a tombstone generation")
        jar.put(cookie("v3"))
        jar.remove(key)
        assertEquals(null, jar.compareAndSet(key, absent, cookie("v2")), "absent then is not absent now")
        assertEquals(null, jar.find(key))
        assertEquals(true, jar.compareAndSet(key, jar.read(key), cookie("v2")) != null)
        assertEquals(null, jar.compareAndSet(key, JarState(null, jar.read(key).generation), null), "present is not absent at the same generation")
    }

    /** Positive control: once the store holds v3, the page's deletion does reach the jar. */
    @Test
    fun aDeletionAfterTheStoreHeldTheRecordReachesTheJar() {
        val jar = MemoryWebViewCookieJar { now }
        jar.put(cookie("v1"))
        val sync = CookieSync({ jar }, { now })
        sync.preload("site.test")
        sync.reconcile(listOf("site.test"), listOf(cookie("v1")), 1)
        jar.put(cookie("v3"))
        sync.reconcile(listOf("site.test"), listOf(cookie("v3")), 1) // the push landed
        sync.reconcile(listOf("site.test"), emptyList(), 1)
        assertEquals(null, jar.find(cookie("x").key))
    }
}
