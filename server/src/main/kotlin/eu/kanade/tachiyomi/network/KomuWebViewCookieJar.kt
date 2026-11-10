package eu.kanade.tachiyomi.network

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.Cookie
import xyz.nulldev.androidcompat.webkit.ios.VersionedCookie
import xyz.nulldev.androidcompat.webkit.ios.WebCookie
import xyz.nulldev.androidcompat.webkit.ios.WebViewCookieJar

/**
 * Komu (ADR-0087 Review 1): the server's persistent jar as the iOS WebView sessions
 * see it, versioned by the store's per-identity generations (compare-and-set under
 * the store's lock). OkHttp's [Cookie] already carries every RFC 6265 attribute (host-only,
 * path, Secure, HttpOnly, SameSite, expiry), so nothing is lost either way.
 */
class KomuWebViewCookieJar(
    private val store: PersistentCookieStore,
) : WebViewCookieJar {
    override fun cookiesFor(host: String): List<VersionedCookie> =
        store
            .allVersioned()
            .map { (cookie, generation) -> VersionedCookie(cookie.toWebCookie(), generation) }
            .filter { it.cookie.appliesTo(host) }

    override fun generation(key: WebCookie.Key): Long = store.generation(key.identity)

    override fun epoch(): Long = store.epoch()

    override fun compareAndSet(
        key: WebCookie.Key,
        expected: Long,
        cookie: WebCookie?,
    ): Long? {
        val okhttp = cookie?.let { it.toOkHttp() ?: return null }
        return store.compareAndSet(key.identity, expected, okhttp)
    }

    companion object {
        private val WebCookie.Key.identity get() = PersistentCookieStore.Identity(name, domain, path)

        fun Cookie.toWebCookie(): WebCookie =
            WebCookie.canonical(
                name = name,
                value = value,
                domain = domain,
                hostOnly = hostOnly,
                path = path,
                secure = secure,
                httpOnly = httpOnly,
                sameSite = sameSite,
                expiresAt = if (persistent) expiresAt else null,
            )

        /** Null when OkHttp refuses the cookie (an invalid name, value or domain). */
        fun WebCookie.toOkHttp(): Cookie? =
            runCatching {
                Cookie
                    .Builder()
                    .name(name)
                    .value(value)
                    .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
                    .path(path)
                    .apply {
                        expiresAt?.let { expiresAt(it) }
                        if (secure) secure()
                        if (httpOnly) httpOnly()
                        sameSite?.let { sameSite(it) }
                    }.build()
            }.getOrNull()
    }
}
