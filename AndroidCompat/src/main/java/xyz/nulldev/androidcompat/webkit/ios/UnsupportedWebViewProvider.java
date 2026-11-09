/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package xyz.nulldev.androidcompat.webkit.ios;

/**
 * Komu: a WebViewProvider whose every method reports that this host does not
 * support it. Generated from {@link android.webkit.WebViewProvider}; the iOS
 * provider overrides what the WKWebView engine implements.
 */
@SuppressWarnings({"deprecation", "unused"})
public abstract class UnsupportedWebViewProvider implements android.webkit.WebViewProvider {
    protected static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("WebView." + method + " is not supported by the iOS WebView engine");
    }

    @Override
    public void init(java.util.Map<java.lang.String, java.lang.Object> p0, boolean p1) {
        throw unsupported("init");
    }

    @Override
    public void setHorizontalScrollbarOverlay(boolean p0) {
        throw unsupported("setHorizontalScrollbarOverlay");
    }

    @Override
    public void setVerticalScrollbarOverlay(boolean p0) {
        throw unsupported("setVerticalScrollbarOverlay");
    }

    @Override
    public boolean overlayHorizontalScrollbar() {
        throw unsupported("overlayHorizontalScrollbar");
    }

    @Override
    public boolean overlayVerticalScrollbar() {
        throw unsupported("overlayVerticalScrollbar");
    }

    @Override
    public int getVisibleTitleHeight() {
        throw unsupported("getVisibleTitleHeight");
    }

    @Override
    public android.net.http.SslCertificate getCertificate() {
        throw unsupported("getCertificate");
    }

    @Override
    public void setCertificate(android.net.http.SslCertificate p0) {
        throw unsupported("setCertificate");
    }

    @Override
    public void savePassword(java.lang.String p0, java.lang.String p1, java.lang.String p2) {
        throw unsupported("savePassword");
    }

    @Override
    public void setHttpAuthUsernamePassword(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3) {
        throw unsupported("setHttpAuthUsernamePassword");
    }

    @Override
    public java.lang.String[] getHttpAuthUsernamePassword(java.lang.String p0, java.lang.String p1) {
        throw unsupported("getHttpAuthUsernamePassword");
    }

    @Override
    public void destroy() {
        throw unsupported("destroy");
    }

    @Override
    public void setNetworkAvailable(boolean p0) {
        throw unsupported("setNetworkAvailable");
    }

    @Override
    public android.webkit.WebBackForwardList saveState(android.os.Bundle p0) {
        throw unsupported("saveState");
    }

    @Override
    public boolean savePicture(android.os.Bundle p0, java.io.File p1) {
        throw unsupported("savePicture");
    }

    @Override
    public boolean restorePicture(android.os.Bundle p0, java.io.File p1) {
        throw unsupported("restorePicture");
    }

    @Override
    public android.webkit.WebBackForwardList restoreState(android.os.Bundle p0) {
        throw unsupported("restoreState");
    }

    @Override
    public void loadUrl(java.lang.String p0, java.util.Map<java.lang.String, java.lang.String> p1) {
        throw unsupported("loadUrl");
    }

    @Override
    public void loadUrl(java.lang.String p0) {
        throw unsupported("loadUrl");
    }

    @Override
    public void postUrl(java.lang.String p0, byte[] p1) {
        throw unsupported("postUrl");
    }

    @Override
    public void loadData(java.lang.String p0, java.lang.String p1, java.lang.String p2) {
        throw unsupported("loadData");
    }

    @Override
    public void loadDataWithBaseURL(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3, java.lang.String p4) {
        throw unsupported("loadDataWithBaseURL");
    }

    @Override
    public void evaluateJavaScript(java.lang.String p0, android.webkit.ValueCallback<java.lang.String> p1) {
        throw unsupported("evaluateJavaScript");
    }

    @Override
    public void saveWebArchive(java.lang.String p0) {
        throw unsupported("saveWebArchive");
    }

    @Override
    public void saveWebArchive(java.lang.String p0, boolean p1, android.webkit.ValueCallback<java.lang.String> p2) {
        throw unsupported("saveWebArchive");
    }

    @Override
    public void stopLoading() {
        throw unsupported("stopLoading");
    }

    @Override
    public void reload() {
        throw unsupported("reload");
    }

    @Override
    public boolean canGoBack() {
        throw unsupported("canGoBack");
    }

    @Override
    public void goBack() {
        throw unsupported("goBack");
    }

    @Override
    public boolean canGoForward() {
        throw unsupported("canGoForward");
    }

    @Override
    public void goForward() {
        throw unsupported("goForward");
    }

    @Override
    public boolean canGoBackOrForward(int p0) {
        throw unsupported("canGoBackOrForward");
    }

    @Override
    public void goBackOrForward(int p0) {
        throw unsupported("goBackOrForward");
    }

    @Override
    public boolean isPrivateBrowsingEnabled() {
        throw unsupported("isPrivateBrowsingEnabled");
    }

    @Override
    public boolean pageUp(boolean p0) {
        throw unsupported("pageUp");
    }

    @Override
    public boolean pageDown(boolean p0) {
        throw unsupported("pageDown");
    }

    @Override
    public void insertVisualStateCallback(long p0, android.webkit.WebView.VisualStateCallback p1) {
        throw unsupported("insertVisualStateCallback");
    }

    @Override
    public void clearView() {
        throw unsupported("clearView");
    }

    @Override
    public android.graphics.Picture capturePicture() {
        throw unsupported("capturePicture");
    }

    @Override
    public android.print.PrintDocumentAdapter createPrintDocumentAdapter(java.lang.String p0) {
        throw unsupported("createPrintDocumentAdapter");
    }

    @Override
    public float getScale() {
        throw unsupported("getScale");
    }

    @Override
    public void setInitialScale(int p0) {
        throw unsupported("setInitialScale");
    }

    @Override
    public void invokeZoomPicker() {
        throw unsupported("invokeZoomPicker");
    }

    @Override
    public android.webkit.WebView.HitTestResult getHitTestResult() {
        throw unsupported("getHitTestResult");
    }

    @Override
    public void requestFocusNodeHref(android.os.Message p0) {
        throw unsupported("requestFocusNodeHref");
    }

    @Override
    public void requestImageRef(android.os.Message p0) {
        throw unsupported("requestImageRef");
    }

    @Override
    public java.lang.String getUrl() {
        throw unsupported("getUrl");
    }

    @Override
    public java.lang.String getOriginalUrl() {
        throw unsupported("getOriginalUrl");
    }

    @Override
    public java.lang.String getTitle() {
        throw unsupported("getTitle");
    }

    @Override
    public android.graphics.Bitmap getFavicon() {
        throw unsupported("getFavicon");
    }

    @Override
    public java.lang.String getTouchIconUrl() {
        throw unsupported("getTouchIconUrl");
    }

    @Override
    public int getProgress() {
        throw unsupported("getProgress");
    }

    @Override
    public int getContentHeight() {
        throw unsupported("getContentHeight");
    }

    @Override
    public int getContentWidth() {
        throw unsupported("getContentWidth");
    }

    @Override
    public void pauseTimers() {
        throw unsupported("pauseTimers");
    }

    @Override
    public void resumeTimers() {
        throw unsupported("resumeTimers");
    }

    @Override
    public void onPause() {
        throw unsupported("onPause");
    }

    @Override
    public void onResume() {
        throw unsupported("onResume");
    }

    @Override
    public boolean isPaused() {
        throw unsupported("isPaused");
    }

    @Override
    public void freeMemory() {
        throw unsupported("freeMemory");
    }

    @Override
    public void clearCache(boolean p0) {
        throw unsupported("clearCache");
    }

    @Override
    public void clearFormData() {
        throw unsupported("clearFormData");
    }

    @Override
    public void clearHistory() {
        throw unsupported("clearHistory");
    }

    @Override
    public void clearSslPreferences() {
        throw unsupported("clearSslPreferences");
    }

    @Override
    public android.webkit.WebBackForwardList copyBackForwardList() {
        throw unsupported("copyBackForwardList");
    }

    @Override
    public void setFindListener(android.webkit.WebView.FindListener p0) {
        throw unsupported("setFindListener");
    }

    @Override
    public void findNext(boolean p0) {
        throw unsupported("findNext");
    }

    @Override
    public int findAll(java.lang.String p0) {
        throw unsupported("findAll");
    }

    @Override
    public void findAllAsync(java.lang.String p0) {
        throw unsupported("findAllAsync");
    }

    @Override
    public boolean showFindDialog(java.lang.String p0, boolean p1) {
        throw unsupported("showFindDialog");
    }

    @Override
    public void clearMatches() {
        throw unsupported("clearMatches");
    }

    @Override
    public void documentHasImages(android.os.Message p0) {
        throw unsupported("documentHasImages");
    }

    @Override
    public void setWebViewClient(android.webkit.WebViewClient p0) {
        throw unsupported("setWebViewClient");
    }

    @Override
    public android.webkit.WebViewClient getWebViewClient() {
        throw unsupported("getWebViewClient");
    }

    @Override
    public android.webkit.WebViewRenderProcess getWebViewRenderProcess() {
        throw unsupported("getWebViewRenderProcess");
    }

    @Override
    public void setWebViewRenderProcessClient(java.util.concurrent.Executor p0, android.webkit.WebViewRenderProcessClient p1) {
        throw unsupported("setWebViewRenderProcessClient");
    }

    @Override
    public android.webkit.WebViewRenderProcessClient getWebViewRenderProcessClient() {
        throw unsupported("getWebViewRenderProcessClient");
    }

    @Override
    public void setDownloadListener(android.webkit.DownloadListener p0) {
        throw unsupported("setDownloadListener");
    }

    @Override
    public void setWebChromeClient(android.webkit.WebChromeClient p0) {
        throw unsupported("setWebChromeClient");
    }

    @Override
    public android.webkit.WebChromeClient getWebChromeClient() {
        throw unsupported("getWebChromeClient");
    }

    @Override
    public void setPictureListener(android.webkit.WebView.PictureListener p0) {
        throw unsupported("setPictureListener");
    }

    @Override
    public void addJavascriptInterface(java.lang.Object p0, java.lang.String p1) {
        throw unsupported("addJavascriptInterface");
    }

    @Override
    public void removeJavascriptInterface(java.lang.String p0) {
        throw unsupported("removeJavascriptInterface");
    }

    @Override
    public android.webkit.WebMessagePort[] createWebMessageChannel() {
        throw unsupported("createWebMessageChannel");
    }

    @Override
    public void postMessageToMainFrame(android.webkit.WebMessage p0, android.net.Uri p1) {
        throw unsupported("postMessageToMainFrame");
    }

    @Override
    public android.webkit.WebSettings getSettings() {
        throw unsupported("getSettings");
    }

    @Override
    public void setMapTrackballToArrowKeys(boolean p0) {
        throw unsupported("setMapTrackballToArrowKeys");
    }

    @Override
    public void flingScroll(int p0, int p1) {
        throw unsupported("flingScroll");
    }

    @Override
    public android.view.View getZoomControls() {
        throw unsupported("getZoomControls");
    }

    @Override
    public boolean canZoomIn() {
        throw unsupported("canZoomIn");
    }

    @Override
    public boolean canZoomOut() {
        throw unsupported("canZoomOut");
    }

    @Override
    public boolean zoomBy(float p0) {
        throw unsupported("zoomBy");
    }

    @Override
    public boolean zoomIn() {
        throw unsupported("zoomIn");
    }

    @Override
    public boolean zoomOut() {
        throw unsupported("zoomOut");
    }

    @Override
    public void dumpViewHierarchyWithProperties(java.io.BufferedWriter p0, int p1) {
        throw unsupported("dumpViewHierarchyWithProperties");
    }

    @Override
    public android.view.View findHierarchyView(java.lang.String p0, int p1) {
        throw unsupported("findHierarchyView");
    }

    @Override
    public void setRendererPriorityPolicy(int p0, boolean p1) {
        throw unsupported("setRendererPriorityPolicy");
    }

    @Override
    public int getRendererRequestedPriority() {
        throw unsupported("getRendererRequestedPriority");
    }

    @Override
    public boolean getRendererPriorityWaivedWhenNotVisible() {
        throw unsupported("getRendererPriorityWaivedWhenNotVisible");
    }

    @Override
    public android.webkit.WebViewProvider.ViewDelegate getViewDelegate() {
        throw unsupported("getViewDelegate");
    }

    @Override
    public android.webkit.WebViewProvider.ScrollDelegate getScrollDelegate() {
        throw unsupported("getScrollDelegate");
    }

    @Override
    public void notifyFindDialogDismissed() {
        throw unsupported("notifyFindDialogDismissed");
    }

}
