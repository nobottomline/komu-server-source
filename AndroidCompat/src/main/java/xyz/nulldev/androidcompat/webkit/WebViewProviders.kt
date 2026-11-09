package xyz.nulldev.androidcompat.webkit

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.webkit.WebView
import android.webkit.WebViewProvider
import xyz.nulldev.androidcompat.CallableArgument

/**
 * Komu: selects the `WebViewProvider` for the host. Desktop keeps upstream's KCEF
 * provider. The iOS host (`-Dkomu.platform=ios`, set by Komu's JVM launcher) never
 * starts CEF, so KCEF's provider would block the creating thread forever waiting
 * for it; the iOS factory fails fast instead.
 */
object WebViewProviders {
    const val PLATFORM_PROPERTY = "komu.platform"
    const val IOS = "ios"

    fun isIOS(platform: String? = System.getProperty(PLATFORM_PROPERTY)): Boolean = platform == IOS

    fun factoryFor(platform: String? = System.getProperty(PLATFORM_PROPERTY)): CallableArgument<WebView, WebViewProvider> =
        if (isIOS(platform)) {
            CallableArgument { _: WebView ->
                throw WebViewUnavailableException(
                    WebViewUnavailableException.Reason.NO_ENGINE,
                    "this host has no embedded WebView engine",
                )
            }
        } else {
            CallableArgument { view: WebView -> KcefWebViewProvider(view) }
        }
}
