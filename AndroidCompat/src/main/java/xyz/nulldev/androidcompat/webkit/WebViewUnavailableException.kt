package xyz.nulldev.androidcompat.webkit

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Komu: thrown when an extension creates an `android.webkit.WebView` that this
 * host cannot provide. It is raised synchronously from the WebView constructor
 * (like Android's missing-WebView-package error), so the extension's request fails
 * at once instead of waiting on an engine that will never start.
 */
class WebViewUnavailableException(
    val reason: Reason,
    message: String,
) : RuntimeException("WebView is not available: $message (${reason.name})") {
    enum class Reason {
        /** The host has no WebView engine at all. */
        NO_ENGINE,

        /** The host's native WebView bridge is not linked into this process. */
        NATIVE_BRIDGE_MISSING,

        /** The app is in the background and holds no execution lease. */
        REQUIRES_FOREGROUND,

        /** The engine is shutting down (server stop, memory pressure). */
        ENGINE_STOPPED,
    }
}
