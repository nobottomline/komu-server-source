package eu.kanade.tachiyomi.network

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.Cookie
import xyz.nulldev.androidcompat.webkit.ios.WebCookie
import xyz.nulldev.androidcompat.webkit.ios.WebViewCookieJar

/**
 * Komu (ADR-0086 Review 1): the server's persistent jar as the iOS WebView sessions
 * see it. OkHttp's [Cookie] already carries every RFC 6265 attribute (host-only,
 * path, Secure, HttpOnly, SameSite, expiry), so nothing is lost either way.
 */
class KomuWebViewCookieJar(
    private val store: PersistentCookieStore,
) : WebViewCookieJar {
    override fun cookiesFor(host: String): List<WebCookie> = store.all().map { it.toWebCookie() }.filter { it.appliesTo(host) }

    override fun find(key: WebCookie.Key): WebCookie? =
        store
            .all()
            .firstOrNull { it.name == key.name && it.domain == key.domain && it.path == key.path }
            ?.toWebCookie()

    override fun put(cookie: WebCookie) {
        store.put(cookie.toOkHttp() ?: return)
    }

    override fun remove(key: WebCookie.Key) = store.remove(key.name, key.domain, key.path)

    companion object {
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
