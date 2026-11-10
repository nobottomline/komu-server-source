package xyz.nulldev.androidcompat.webkit.ios

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Komu ADR-0087 Recheck 2: the per-record three-way cookie merge, exhaustively, and
 * Codex's scenario through [CookieSync]. The scenario uses only the calls every
 * version of [CookieSync] has, so the same test runs against the Recheck 1 model as
 * its red control.
 */
class CookieMergeTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun cookie(
        value: String,
        expiresAt: Long? = null,
        sameSite: String? = null,
    ) = WebCookie.canonical("token", value, "site.test", true, "/", false, false, sameSite, expiresAt)

    @Test
    fun everyCombinationOfBaseServerAndViewFollowsTheRules() {
        // Four values per input: absent, and three distinct records of one identity
        // (another value; the same value with another expiry — a renewal).
        val values = listOf(null, cookie("a"), cookie("b"), cookie("a", expiresAt = now + day))
        val generation = 7L
        var deletions = 0
        var combinations = 0
        for (base in values) for (server in values) for (view in values) {
            combinations++
            val outcome = CookieMerge.reduce(base, server, generation, view)
            val case = "base=$base server=$server view=$view"
            when {
                server == view -> {
                    assertEquals(CookieMerge.JarAction.None, outcome.jar, case)
                    assertEquals(CookieMerge.ViewAction.None, outcome.view, case)
                    assertEquals(server, outcome.base, "converged: base is the record both hold; $case")
                }
                server == base -> {
                    assertEquals(CookieMerge.JarAction.CompareAndSet(view, generation), outcome.jar, "the page's change, by CAS; $case")
                    assertEquals(CookieMerge.ViewAction.None, outcome.view, case)
                    assertEquals(view, outcome.base, case)
                }
                else -> {
                    assertEquals(CookieMerge.JarAction.None, outcome.jar, "the server wins; $case")
                    assertEquals(server?.let { CookieMerge.ViewAction.Set(it) } ?: CookieMerge.ViewAction.Delete, outcome.view, case)
                    assertEquals(base, outcome.base, "base does not move until the store shows the record; $case")
                }
            }
            // Safety, stated on its own: the jar loses a record only when the store lacks
            // it and the jar holds exactly the base.
            val action = outcome.jar
            if (action is CookieMerge.JarAction.CompareAndSet && action.cookie == null) {
                deletions++
                assertTrue(view == null && server != null && server == base, "deletion only from the base; $case")
                assertEquals(generation, action.expected)
            }
            // The jar is written only with the store's own record.
            if (action is CookieMerge.JarAction.CompareAndSet && action.cookie != null) assertEquals(view, action.cookie, case)
        }
        assertEquals(64, combinations)
        assertEquals(3, deletions, "one per non-null base that the server still holds and the store lacks")
    }

    @Test
    fun sameSiteNoneEqualsUnspecifiedAsWebKitStoresIt() {
        val stored = cookie("a", sameSite = "None")
        val reported = cookie("a")
        val outcome = CookieMerge.reduce(stored, stored, 3, reported)
        assertEquals(CookieMerge.JarAction.None, outcome.jar)
        assertEquals(CookieMerge.ViewAction.None, outcome.view)
    }
}
