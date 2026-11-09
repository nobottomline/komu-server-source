/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package xyz.nulldev.androidcompat.webkit.ios;

/**
 * Komu: the off-screen engine has no Android view to drive; view plumbing that
 * WebView routes to its provider (layout, focus, input) is accepted and ignored.
 * Generated from {@link android.webkit.WebViewProvider.ScrollDelegate}.
 */
@SuppressWarnings({"deprecation", "unused"})
public class InertScrollDelegate implements android.webkit.WebViewProvider.ScrollDelegate {
    @Override
    public int computeHorizontalScrollRange() {
        return 0;
    }

    @Override
    public int computeHorizontalScrollOffset() {
        return 0;
    }

    @Override
    public int computeVerticalScrollRange() {
        return 0;
    }

    @Override
    public int computeVerticalScrollOffset() {
        return 0;
    }

    @Override
    public int computeVerticalScrollExtent() {
        return 0;
    }

    @Override
    public void computeScroll() {}

}
