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
 * Generated from {@link android.webkit.WebViewProvider.ViewDelegate}.
 */
@SuppressWarnings({"deprecation", "unused"})
public class InertViewDelegate implements android.webkit.WebViewProvider.ViewDelegate {
    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    @Override
    public void onProvideVirtualStructure(android.view.ViewStructure p0) {}

    @Override
    public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return null;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo p0) {}

    @Override
    public void onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent p0) {}

    @Override
    public boolean performAccessibilityAction(int p0, android.os.Bundle p1) {
        return false;
    }

    @Override
    public void setOverScrollMode(int p0) {}

    @Override
    public void setScrollBarStyle(int p0) {}

    @Override
    public void onDrawVerticalScrollBar(android.graphics.Canvas p0, android.graphics.drawable.Drawable p1, int p2, int p3, int p4, int p5) {}

    @Override
    public void onOverScrolled(int p0, int p1, boolean p2, boolean p3) {}

    @Override
    public void onWindowVisibilityChanged(int p0) {}

    @Override
    public void onDraw(android.graphics.Canvas p0) {}

    @Override
    public void setLayoutParams(android.view.ViewGroup.LayoutParams p0) {}

    @Override
    public boolean performLongClick() {
        return false;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration p0) {}

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo p0) {
        return null;
    }

    @Override
    public boolean onDragEvent(android.view.DragEvent p0) {
        return false;
    }

    @Override
    public boolean onKeyMultiple(int p0, int p1, android.view.KeyEvent p2) {
        return false;
    }

    @Override
    public boolean onKeyDown(int p0, android.view.KeyEvent p1) {
        return false;
    }

    @Override
    public boolean onKeyUp(int p0, android.view.KeyEvent p1) {
        return false;
    }

    @Override
    public void onAttachedToWindow() {}

    @Override
    public void onDetachedFromWindow() {}

    @Override
    public void onVisibilityChanged(android.view.View p0, int p1) {}

    @Override
    public void onWindowFocusChanged(boolean p0) {}

    @Override
    public void onFocusChanged(boolean p0, int p1, android.graphics.Rect p2) {}

    @Override
    public boolean setFrame(int p0, int p1, int p2, int p3) {
        return false;
    }

    @Override
    public void onSizeChanged(int p0, int p1, int p2, int p3) {}

    @Override
    public void onScrollChanged(int p0, int p1, int p2, int p3) {}

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent p0) {
        return false;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent p0) {
        return false;
    }

    @Override
    public boolean onHoverEvent(android.view.MotionEvent p0) {
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent p0) {
        return false;
    }

    @Override
    public boolean onTrackballEvent(android.view.MotionEvent p0) {
        return false;
    }

    @Override
    public boolean requestFocus(int p0, android.graphics.Rect p1) {
        return false;
    }

    @Override
    public void onMeasure(int p0, int p1) {}

    @Override
    public boolean requestChildRectangleOnScreen(android.view.View p0, android.graphics.Rect p1, boolean p2) {
        return false;
    }

    @Override
    public void setBackgroundColor(int p0) {}

    @Override
    public void setLayerType(int p0, android.graphics.Paint p1) {}

    @Override
    public void preDispatchDraw(android.graphics.Canvas p0) {}

    @Override
    public void onStartTemporaryDetach() {}

    @Override
    public void onFinishTemporaryDetach() {}

    @Override
    public void onActivityResult(int p0, int p1, android.content.Intent p2) {}

    @Override
    public android.os.Handler getHandler(android.os.Handler p0) {
        return null;
    }

    @Override
    public android.view.View findFocus(android.view.View p0) {
        return null;
    }

}
