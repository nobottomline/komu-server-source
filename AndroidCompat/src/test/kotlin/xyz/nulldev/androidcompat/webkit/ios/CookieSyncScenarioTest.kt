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
 * Komu ADR-0087 Recheck 2: Codex's cookie scenario through [CookieSync]. Only calls
 * every version of [CookieSync] has, so the same file runs against the Recheck 1
 * model as its red control.
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
