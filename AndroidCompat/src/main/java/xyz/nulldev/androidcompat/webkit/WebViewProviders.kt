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
import xyz.nulldev.androidcompat.webkit.ios.NativeWebEngine
import xyz.nulldev.androidcompat.webkit.ios.WKWebViewProvider
import xyz.nulldev.androidcompat.webkit.ios.WKWebViewSessions
import xyz.nulldev.androidcompat.webkit.ios.WebEngine

/**
 * Komu: selects the `WebViewProvider` for the host. Desktop keeps upstream's KCEF
 * provider. The iOS host (`-Dkomu.platform=ios`, set by Komu's JVM launcher) never
 * starts CEF, so KCEF's provider would block the creating thread forever waiting
 * for it. There, the off-screen WKWebView engine linked into the app backs
 * [WKWebViewProvider]; a process without that engine fails fast with a typed error.
 */
object WebViewProviders {
    const val PLATFORM_PROPERTY = "komu.platform"
    const val IOS = "ios"

    /** AndroidX WebKit features the iOS provider implements through the boundary glue. */
    val IOS_GLUE_FEATURES = listOf("DOCUMENT_START_SCRIPT:1", "WEB_MESSAGE_LISTENER")

    @Volatile
    private var iosEngine: Lazy<WebEngine?> = lazy { NativeWebEngine.linkedOrNull() }

    @Volatile
    private var iosSelected = false

    fun isIOS(platform: String? = System.getProperty(PLATFORM_PROPERTY)): Boolean = platform == IOS

    fun factoryFor(platform: String? = System.getProperty(PLATFORM_PROPERTY)): CallableArgument<WebView, WebViewProvider> =
        if (isIOS(platform)) {
            iosSelected = true
            CallableArgument { view: WebView ->
                val engine =
                    iosEngine.value ?: throw WebViewUnavailableException(
                        WebViewUnavailableException.Reason.NATIVE_BRIDGE_MISSING,
                        "this process does not link the iOS WebView engine",
                    )
                WKWebViewSessions.install(engine)
                WKWebViewProvider(view, engine)
            }
        } else {
            iosSelected = false
            CallableArgument { view: WebView -> KcefWebViewProvider(view) }
        }

    /** The glue's supported features: [base] plus those of the selected provider. */
    @JvmStatic
    fun glueFeatures(base: Array<String>): Array<String> =
        if (iosSelected && iosEngine.value != null) (base.toList() + IOS_GLUE_FEATURES).distinct().toTypedArray() else base

    /** Tests: back the iOS provider with [engine] (null: no engine linked). */
    fun installIOSEngineForTesting(engine: WebEngine?) {
        iosEngine = lazyOf(engine)
    }
}
