package xyz.nulldev.androidcompat.webkit.ios

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.net.Uri
import android.os.Handler
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewProvider.ScrollDelegate
import android.webkit.WebViewProvider.ViewDelegate
import app.komu.bridge.JsExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface
import org.chromium.support_lib_boundary.WebMessageListenerBoundaryInterface
import org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface
import org.chromium.support_lib_boundary.util.BoundaryInterfaceReflectionUtil
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import xyz.nulldev.androidcompat.webkit.WebViewUnavailableException
import java.lang.reflect.InvocationHandler
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Komu: `android.webkit.WebView` on the iOS host, backed by an off-screen WKWebView
 * owned by the app ([JsExecutor], `JsExecutor.m`).
 *
 * Android semantics kept:
 * - `WebViewClient` page events, `evaluateJavascript` results and web-message
 *   listeners run on the WebView's looper (the main looper for `runWebView`);
 *   `shouldInterceptRequest` and `@JavascriptInterface` calls run on background
 *   threads, interface calls in order.
 * - `evaluateJavascript` reports the script's completion value as JSON (`"null"`
 *   for `undefined` or a thrown error).
 * - Only `@JavascriptInterface` methods are callable from the page.
 * - One User-Agent per session: `settings.userAgentString` goes with every load
 *   (URL and data alike).
 *
 * iOS differences (WebKit cannot do otherwise without private API):
 * - `shouldInterceptRequest` observes; a non-null response blocks sub-frame
 *   navigations only. Responses are never served in place of the network.
 *   Sub-resources (fetch, XHR, images, scripts) are reported, not held.
 * - JavaScript-interface methods return `undefined` to the page.
 * - Object keys of evaluation results come sorted.
 *
 * Cookies ([CookieSync]): before each load the server's cookies for the load's host
 * are copied into the session's own (non-persistent) website data store with every
 * RFC 6265 attribute; the session's cookie set is reconciled back into the server's
 * jar (additions, changes and deletions) for its target hosts only.
 *
 * Page messages (`@JavascriptInterface` calls, web messages) are credit-bounded:
 * the engine drops what exceeds the session's budget, this side returns each
 * message's cost once handled, and a drop reaches the client as
 * `onReceivedError(ERROR_TOO_MANY_REQUESTS)`.
 *
 * Egress (ADR-0063 §6): a session waiting for a slot declares each load to the
 * engine at once ([JsExecutor.OP_BIND]), so its binding exists before it runs.
 */
class WKWebViewProvider(
    private val view: WebView,
    private val engine: WebEngine,
) : UnsupportedWebViewProvider(),
    WebViewProviderBoundaryInterface {
    private val settings = KcefWebSettings()
    private val handler = Handler(view.webViewLooper)
    private val bridgeQueue = SerialExecutor(WKWebViewSessions.background)

    @Volatile
    private var viewClient = WebViewClient()

    @Volatile
    private var chromeClient = WebChromeClient()

    @Volatile
    internal var handle: Long = 0L
        private set

    private val lock = Any()
    private var active = false
    private var destroyed = false
    private var gone = false
    private val pending = ArrayList<Triple<Int, Long, List<String>>>()

    private val ids = AtomicLong()
    private val evaluations = ConcurrentHashMap<Long, ValueCallback<String>>()
    private val interfaces = ConcurrentHashMap<String, Any>()
    private val messageListeners = ConcurrentHashMap<String, InvocationHandler>()
    private val cookies = CookieSync({ WebViewCookies.jarOrWarn() })
    private val budget = MessageBudget()

    @Volatile
    private var interceptionEnabled = false

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var progress = 0

    private var openedAt = 0L

    // ------------------------------------------------------------------ lifecycle

    override fun init(
        javaScriptInterfaces: MutableMap<String, Any>?,
        privateBrowsing: Boolean,
    ) {
        val availability = engine.availability()
        if (availability == JsExecutor.REQUIRES_IOS_17) {
            throw WebViewUnavailableException(
                WebViewUnavailableException.Reason.REQUIRES_IOS_17,
                "extension WebViews need iOS 17 or later",
            )
        }
        if (availability == JsExecutor.REQUIRES_FOREGROUND) {
            throw WebViewUnavailableException(
                WebViewUnavailableException.Reason.REQUIRES_FOREGROUND,
                "the app is in the background without an execution lease",
            )
        }
        if (availability != JsExecutor.AVAILABLE) {
            throw WebViewUnavailableException(WebViewUnavailableException.Reason.ENGINE_STOPPED, "the engine is stopped")
        }
        handle = engine.create()
        if (handle == 0L) {
            throw WebViewUnavailableException(
                WebViewUnavailableException.Reason.REQUIRES_FOREGROUND,
                "the engine refused a new session",
            )
        }
        openedAt = System.nanoTime()
        WKWebViewSessions.open(this)
        logger.info { "WebView session $handle opened (${WKWebViewSessions.openSessions()} open)" }
        javaScriptInterfaces?.forEach { (name, obj) -> addJavascriptInterface(obj, name) }
    }

    /**
     * Called once a session slot is granted: sends the commands buffered until then.
     * False when the session ended while it waited (destroyed, or ended by the engine,
     * e.g. its task binding was revoked): it never runs, and the slot goes on.
     */
    internal fun activate(): Boolean {
        synchronized(lock) {
            if (destroyed || gone) {
                pending.clear()
                return false
            }
            active = true
            for ((op, id, fields) in pending) engine.submit(handle, op, id, fields)
            pending.clear()
            return true
        }
    }

    internal fun hasEnded(): Boolean = synchronized(lock) { destroyed || gone }

    private fun submit(
        op: Int,
        id: Long = 0L,
        fields: List<String> = emptyList(),
    ) {
        synchronized(lock) {
            if (destroyed || gone) return
            if (!active) {
                pending += Triple(op, id, fields)
                // ADR-0063 §6: the engine admits and binds the load now, so ending the
                // task work it reaches ends this session before it ever runs.
                if (op == JsExecutor.OP_LOAD_URL || op == JsExecutor.OP_LOAD_DATA) {
                    engine.submit(handle, JsExecutor.OP_BIND, 0L, listOf(fields.firstOrNull().orEmpty()))
                }
                return
            }
            engine.submit(handle, op, id, fields)
        }
    }

    override fun destroy() {
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            pending.clear()
        }
        evaluations.clear()
        interfaces.clear()
        messageListeners.clear()
        if (handle != 0L) {
            WKWebViewSessions.close(this)
            engine.destroy(handle)
            logger.info { "WebView session $handle closed after ${(System.nanoTime() - openedAt) / 1_000_000} ms" }
        }
    }

    // ------------------------------------------------------------------ loading

    override fun loadUrl(
        url: String,
        additionalHttpHeaders: MutableMap<String, String>?,
    ) = load(url, additionalHttpHeaders.orEmpty(), "GET", "")

    override fun loadUrl(url: String) = loadUrl(url, null)

    override fun postUrl(
        url: String,
        postData: ByteArray,
    ) = load(
        url,
        emptyMap(),
        "POST",
        java.util.Base64
            .getEncoder()
            .encodeToString(postData),
    )

    private fun load(
        url: String,
        headers: Map<String, String>,
        method: String,
        bodyBase64: String,
    ) {
        if (url.startsWith("javascript:")) {
            evaluateJavaScript(url.removePrefix("javascript:"), null)
            return
        }
        beginLoad(url)
        val (preload, preloaded) = cookies.preload(hostOf(url))
        pushDeletions(preloaded)
        submit(
            JsExecutor.OP_LOAD_URL,
            fields =
                listOf(
                    url,
                    headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                    sessionUserAgent(),
                    CookieWire.encode(preloaded.set),
                    flags(),
                    method,
                    bodyBase64,
                    preload.toString(),
                ),
        )
    }

    override fun loadData(
        data: String,
        mimeType: String?,
        encoding: String?,
    ) = loadDataWithBaseURL(null, data, mimeType, encoding, null)

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        val base = baseUrl ?: "about:blank"
        beginLoad(base)
        val (preload, preloaded) = cookies.preload(hostOf(base))
        pushDeletions(preloaded)
        submit(
            JsExecutor.OP_LOAD_DATA,
            fields =
                listOf(
                    base,
                    data,
                    mimeType ?: "text/html",
                    encoding ?: "",
                    sessionUserAgent(),
                    CookieWire.encode(preloaded.set),
                    flags(),
                    historyUrl ?: "",
                    preload.toString(),
                ),
        )
    }

    /** Server deletions since the base go to the store ahead of the load's preload. */
    private fun pushDeletions(push: CookiePush) {
        if (push.delete.isNotEmpty()) submit(JsExecutor.OP_SET_COOKIES, fields = listOf("[]", CookieWire.encode(push.delete)))
    }

    private fun beginLoad(url: String) {
        currentUrl = url
        progress = 0
    }

    /** The session's single User-Agent: what the extension set, else the server's default. */
    private fun sessionUserAgent(): String = settings.userAgentString.orEmpty()

    private fun flags(): String = "js=" + (if (settings.javaScriptEnabled) "1" else "0")

    /**
     * Android calls back once the DOM state at posting time can be drawn. A round trip
     * through the page's process orders the callback after that state.
     */
    override fun insertVisualStateCallback(
        requestId: Long,
        callback: WebView.VisualStateCallback,
    ) = evaluateJavaScript("0") { callback.onComplete(requestId) }

    override fun stopLoading() = submit(JsExecutor.OP_STOP)

    override fun reload() = submit(JsExecutor.OP_RELOAD)

    // ------------------------------------------------------------------ JavaScript

    override fun evaluateJavaScript(
        script: String,
        resultCallback: ValueCallback<String>?,
    ) {
        val id = ids.incrementAndGet()
        evaluations[id] = resultCallback ?: IGNORED_RESULT
        submit(JsExecutor.OP_EVALUATE, id, listOf(script.removePrefix("javascript:")))
    }

    override fun addJavascriptInterface(
        obj: Any,
        interfaceName: String,
    ) {
        val methods = JsBridge.exposedMethodNames(obj)
        interfaces[interfaceName] = obj
        submit(JsExecutor.OP_ADD_INTERFACE, fields = listOf(interfaceName, JsonArray(methods.map(::JsonPrimitive)).toString()))
    }

    override fun removeJavascriptInterface(interfaceName: String) {
        interfaces.remove(interfaceName)
        submit(JsExecutor.OP_REMOVE_INTERFACE, fields = listOf(interfaceName))
    }

    // ------------------------------------------------------------------ clients, settings

    override fun setWebViewClient(client: WebViewClient) {
        viewClient = client
        val intercepts = overridesShouldInterceptRequest(client)
        if (intercepts != interceptionEnabled) {
            interceptionEnabled = intercepts
            submit(JsExecutor.OP_SET_INTERCEPTION, fields = listOf(if (intercepts) "1" else "0"))
        }
    }

    override fun getWebViewClient(): WebViewClient = viewClient

    override fun setWebChromeClient(client: WebChromeClient?) {
        chromeClient = client ?: WebChromeClient()
    }

    override fun getWebChromeClient(): WebChromeClient = chromeClient

    override fun getSettings(): WebSettings = settings

    override fun getUrl(): String? = currentUrl

    override fun getOriginalUrl(): String? = currentUrl

    override fun getTitle(): String? = null

    override fun getProgress(): Int = progress

    override fun isPrivateBrowsingEnabled(): Boolean = false

    override fun getViewDelegate(): ViewDelegate = InertViewDelegate()

    override fun getScrollDelegate(): ScrollDelegate = InertScrollDelegate()

    override fun setDownloadListener(listener: DownloadListener?) = Unit

    override fun pauseTimers() = Unit

    override fun resumeTimers() = Unit

    override fun onPause() = Unit

    override fun onResume() = Unit

    override fun isPaused(): Boolean = false

    override fun freeMemory() = Unit

    override fun clearCache(includeDiskFiles: Boolean) = Unit

    override fun clearFormData() = Unit

    override fun clearHistory() = Unit

    override fun clearSslPreferences() = Unit

    override fun canGoBack(): Boolean = false

    override fun canGoForward(): Boolean = false

    override fun setNetworkAvailable(networkUp: Boolean) = Unit

    override fun setInitialScale(scaleInPercent: Int) = Unit

    override fun getScale(): Float = 1f

    override fun getContentHeight(): Int = 0

    override fun getContentWidth(): Int = 0

    override fun setMapTrackballToArrowKeys(setMap: Boolean) = Unit

    override fun setFindListener(listener: WebView.FindListener?) = Unit

    override fun clearMatches() = Unit

    override fun notifyFindDialogDismissed() = Unit

    override fun setPictureListener(listener: WebView.PictureListener?) = Unit

    override fun setWebViewRenderProcessClient(
        executor: java.util.concurrent.Executor?,
        client: android.webkit.WebViewRenderProcessClient?,
    ) = Unit

    override fun getWebViewRenderProcessClient(): android.webkit.WebViewRenderProcessClient? = null

    override fun getWebViewRenderProcess(): android.webkit.WebViewRenderProcess? = null

    override fun setRendererPriorityPolicy(
        rendererRequestedPriority: Int,
        waivedWhenNotVisible: Boolean,
    ) = Unit

    override fun getRendererRequestedPriority(): Int = 0

    override fun getRendererPriorityWaivedWhenNotVisible(): Boolean = false

    // ------------------------------------------------------------------ AndroidX WebKit boundary

    override fun setAudioMuted(muted: Boolean) {
        // Media never plays without a user gesture in the engine's sessions.
    }

    override fun addDocumentStartJavaScript(
        script: String,
        allowedOriginRules: Array<String>,
    ): InvocationHandler {
        val rules = OriginRules.validated(allowedOriginRules)
        val id = ids.incrementAndGet()
        submit(JsExecutor.OP_ADD_DOC_START, id, listOf(script, rules))
        return BoundaryInterfaceReflectionUtil.createInvocationHandlerFor(
            object : ScriptHandlerBoundaryInterface {
                override fun remove() = submit(JsExecutor.OP_REMOVE_DOC_START, id)
            },
        )!!
    }

    override fun getJavaScriptWorld(worldName: String): Int = throw UnsupportedOperationException("JS_INJECTION_IN_FRAME_AND_WORLD")

    override fun addJavaScriptOnEvent(
        script: String,
        allowedOriginRules: Array<String>,
        event: Int,
        worldName: String,
    ): InvocationHandler = throw UnsupportedOperationException("JS_INJECTION_IN_FRAME_AND_WORLD")

    override fun addWebMessageListener(
        jsObjectName: String,
        allowedOriginRules: Array<String>,
        listener: InvocationHandler,
    ) {
        val rules = OriginRules.validated(allowedOriginRules)
        require(messageListeners.putIfAbsent(jsObjectName, listener) == null) { "$jsObjectName is already registered" }
        submit(JsExecutor.OP_ADD_LISTENER, fields = listOf(jsObjectName, rules))
    }

    override fun removeWebMessageListener(jsObjectName: String) {
        messageListeners.remove(jsObjectName)
        submit(JsExecutor.OP_REMOVE_LISTENER, fields = listOf(jsObjectName))
    }

    // ------------------------------------------------------------------ engine events (event thread)

    internal fun onEngineEvent(event: WebEngineEvent) {
        when (event.kind) {
            JsExecutor.EV_PAGE_STARTED -> {
                val url = event.field(0)
                currentUrl = url
                onLooper { viewClient.onPageStarted(view, url, null) }
            }

            JsExecutor.EV_PAGE_FINISHED -> {
                val url = event.field(0)
                onLooper {
                    progress = 100
                    viewClient.onPageFinished(view, url)
                    chromeClient.onProgressChanged(view, 100)
                }
            }

            JsExecutor.EV_LOAD_ERROR -> {
                val request = KomuWebResourceRequest(event.field(0), "GET", event.field(3) == "1", false, emptyMap())
                val code = androidErrorCode(event.field(1).toIntOrNull() ?: 0)
                val description = event.field(2)
                val error =
                    object : WebResourceError() {
                        override fun getErrorCode(): Int = code

                        override fun getDescription(): CharSequence = description
                    }
                onLooper { viewClient.onReceivedError(view, request, error) }
            }

            JsExecutor.EV_EVAL_RESULT -> {
                val callback = evaluations.remove(event.id) ?: return
                val json = event.field(0)
                onLooper { callback.onReceiveValue(json) }
            }

            JsExecutor.EV_BRIDGE_CALL -> {
                val cost = event.field(3).toLongOrNull() ?: 0L
                val target = interfaces[event.field(0)]
                if (target == null || !budget.take(cost)) {
                    if (target != null) reportDropped(1)
                    acknowledge(cost, taken = false)
                    return
                }
                val method = event.field(1)
                val arguments = event.field(2)
                bridgeQueue.execute {
                    try {
                        if (!isDestroyed()) JsBridge.invoke(target, method, arguments)
                    } finally {
                        acknowledge(cost, taken = true)
                    }
                }
            }

            JsExecutor.EV_WEB_MESSAGE -> {
                deliverWebMessage(event)
            }

            JsExecutor.EV_REQUEST -> {
                intercept(event)
            }

            JsExecutor.EV_COOKIES -> {
                val push =
                    cookies.reconcile(
                        CookieWire.decodeHosts(event.field(0)),
                        CookieWire.decode(event.field(1)),
                        event.field(2).toLongOrNull() ?: 0L,
                    )
                // Recheck 2: the server's changes go back into the store, so the two converge.
                if (!push.isEmpty()) {
                    submit(JsExecutor.OP_SET_COOKIES, fields = listOf(CookieWire.encode(push.set), CookieWire.encode(push.delete)))
                }
            }

            JsExecutor.EV_MESSAGES_DROPPED -> {
                reportDropped(event.field(0).toIntOrNull() ?: 1)
            }

            JsExecutor.EV_PROCESS_GONE -> {
                synchronized(lock) { gone = true }
                WKWebViewSessions.ended(this)
                evaluations.clear()
                val reason = event.field(1)
                logger.info { "WebView session ended by the engine: $reason" }
                val detail =
                    object : RenderProcessGoneDetail() {
                        override fun didCrash(): Boolean = event.field(0) == "1"

                        override fun rendererPriorityAtExit(): Int = -1
                    }
                onLooper {
                    if (!viewClient.onRenderProcessGone(view, detail)) {
                        logger.warn { "WebView render process gone ($reason) and the client did not handle it" }
                    }
                }
            }
        }
    }

    private fun isDestroyed(): Boolean = synchronized(lock) { destroyed }

    private fun onLooper(block: () -> Unit) {
        handler.post {
            if (isDestroyed()) return@post
            try {
                block()
            } catch (t: Throwable) {
                logger.warn(t) { "WebView client callback failed" }
            }
        }
    }

    private fun intercept(event: WebEngineEvent) {
        val decision = event.id
        if (!interceptionEnabled) {
            if (decision != 0L) submit(JsExecutor.OP_DECIDE, decision, listOf("1"))
            return
        }
        val headers =
            event
                .field(4)
                .lineSequence()
                .mapNotNull { line -> line.split(": ", limit = 2).takeIf { it.size == 2 } }
                .associate { it[0] to it[1] }
        val request = KomuWebResourceRequest(event.field(0), event.field(1), event.field(2) == "1", event.field(3) == "1", headers)
        WKWebViewSessions.background.execute {
            val response =
                if (isDestroyed()) {
                    null
                } else {
                    try {
                        viewClient.shouldInterceptRequest(view, request)
                    } catch (t: Throwable) {
                        logger.warn(t) { "shouldInterceptRequest failed" }
                        null
                    }
                }
            runCatching { response?.data?.close() }
            if (decision != 0L) submit(JsExecutor.OP_DECIDE, decision, listOf(if (response == null) "1" else "0"))
        }
    }

    /** Returns a message's cost to the engine (and to this side's budget when it was taken). */
    private fun acknowledge(
        cost: Long,
        taken: Boolean,
    ) {
        if (taken) budget.release(cost)
        submit(JsExecutor.OP_ACK, fields = listOf(cost.toString()))
    }

    /** Page messages were dropped over the session's budget: a typed error, like Android's for too many requests. */
    private fun reportDropped(count: Int) {
        logger.warn { "WebView session $handle dropped $count page message(s) over its budget" }
        val request = KomuWebResourceRequest(currentUrl.orEmpty(), "GET", false, false, emptyMap())
        val error =
            object : WebResourceError() {
                override fun getErrorCode(): Int = WebViewClient.ERROR_TOO_MANY_REQUESTS

                override fun getDescription(): CharSequence = "$count JavaScript message(s) dropped: the page sends faster than the extension handles them"
            }
        onLooper { viewClient.onReceivedError(view, request, error) }
    }

    private fun deliverWebMessage(event: WebEngineEvent) {
        val cost = event.field(4).toLongOrNull() ?: 0L
        val listener = messageListeners[event.field(0)]
        if (listener == null || !budget.take(cost)) {
            if (listener != null) reportDropped(1)
            acknowledge(cost, taken = false)
            return
        }
        val replyId = event.id
        val message =
            BoundaryInterfaceReflectionUtil.createInvocationHandlerFor(WebMessageDelegate(event.field(1)))
        val reply =
            BoundaryInterfaceReflectionUtil.createInvocationHandlerFor(
                ReplyProxyDelegate { submit(JsExecutor.OP_POST_REPLY, replyId, listOf(it)) },
            )
        val origin = Uri.parse(event.field(2))
        val isMainFrame = event.field(3) == "1"
        val delivered =
            handler.post {
                try {
                    if (!isDestroyed()) {
                        BoundaryInterfaceReflectionUtil
                            .castToSuppLibClass(WebMessageListenerBoundaryInterface::class.java, listener)!!
                            .onPostMessage(view, message, origin, isMainFrame, reply)
                    }
                } catch (t: Throwable) {
                    logger.warn(t) { "WebView message listener failed" }
                } finally {
                    acknowledge(cost, taken = true)
                }
            }
        if (!delivered) acknowledge(cost, taken = true)
    }

    companion object {
        private val IGNORED_RESULT = ValueCallback<String> { }

        internal fun hostOf(url: String): String? =
            runCatching { URI(url) }
                .getOrNull()
                ?.takeIf { it.scheme == "http" || it.scheme == "https" }
                ?.host
                ?.lowercase()

        /** Whether [client] (or a superclass below WebViewClient) overrides shouldInterceptRequest. */
        internal fun overridesShouldInterceptRequest(client: WebViewClient): Boolean {
            var cls: Class<*>? = client.javaClass
            while (cls != null && cls != WebViewClient::class.java) {
                if (cls.declaredMethods.any { m ->
                        m.name == "shouldInterceptRequest" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0] == WebView::class.java &&
                            (m.parameterTypes[1] == WebResourceRequest::class.java || m.parameterTypes[1] == String::class.java)
                    }
                ) {
                    return true
                }
                cls = cls.superclass
            }
            return false
        }

        /** NSURLError codes → `WebViewClient.ERROR_*`. */
        internal fun androidErrorCode(nsUrlError: Int): Int =
            when (nsUrlError) {
                -1003, -1006 -> WebViewClient.ERROR_HOST_LOOKUP
                -1004, -1005, -1009, -1018, -1020 -> WebViewClient.ERROR_CONNECT
                -1001 -> WebViewClient.ERROR_TIMEOUT
                -1007 -> WebViewClient.ERROR_REDIRECT_LOOP
                -1002 -> WebViewClient.ERROR_UNSUPPORTED_SCHEME
                -1000 -> WebViewClient.ERROR_BAD_URL
                -1012, -1013 -> WebViewClient.ERROR_AUTHENTICATION
                -1100 -> WebViewClient.ERROR_FILE_NOT_FOUND
                in -1206..-1200 -> WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                -1011, -1017 -> WebViewClient.ERROR_IO
                else -> WebViewClient.ERROR_UNKNOWN
            }
    }
}

/** `WebResourceRequest` for observed requests and errors. */
internal class KomuWebResourceRequest(
    private val url: String,
    private val method: String,
    private val mainFrame: Boolean,
    private val redirect: Boolean,
    private val headers: Map<String, String>,
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)

    override fun isForMainFrame(): Boolean = mainFrame

    override fun isRedirect(): Boolean = redirect

    override fun hasGesture(): Boolean = false

    override fun getMethod(): String = method

    override fun getRequestHeaders(): Map<String, String> = headers
}

/**
 * This side's guard on a session's page messages, mirroring the engine's budget
 * (`JsExecutor.m`): at most [MAX_MESSAGES] messages or [MAX_BYTES] of them in
 * hand at once; a single message always fits when none is in hand.
 */
internal class MessageBudget {
    private var messages = 0
    private var bytes = 0L

    @Synchronized
    fun take(cost: Long): Boolean {
        if (messages > 0 && (messages >= MAX_MESSAGES || bytes + cost > MAX_BYTES)) return false
        messages++
        bytes += cost
        return true
    }

    @Synchronized
    fun release(cost: Long) {
        messages = maxOf(0, messages - 1)
        bytes = maxOf(0L, bytes - cost)
    }

    @Synchronized
    fun inHand(): Pair<Int, Long> = messages to bytes

    companion object {
        const val MAX_MESSAGES = 256
        const val MAX_BYTES = 4L shl 20
    }
}

/** Android's allowed-origin-rule syntax: `*`, or `scheme://host[:port]` with an optional `*.` host prefix. */
internal object OriginRules {
    private val rule = Regex("""^(https?)://(\*\.)?[A-Za-z0-9.\-\[\]:]+?(:\d+)?$""")

    /** The rules as a JSON array; IllegalArgumentException for an invalid rule (like Android). */
    fun validated(rules: Array<String>): String {
        require(rules.isNotEmpty()) { "allowedOriginRules is empty" }
        for (r in rules) require(r == "*" || rule.matches(r)) { "Invalid allowed origin rule: $r" }
        return JsonArray(rules.map(::JsonPrimitive)).toString()
    }
}

/** Calls `@JavascriptInterface` methods with JSON arguments from the page. */
internal object JsBridge {
    fun exposedMethodNames(target: Any): List<String> =
        target.javaClass.methods
            .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
            .map { it.name }
            .distinct()
            .sorted()

    fun invoke(
        target: Any,
        methodName: String,
        argumentsJson: String,
    ) {
        val arguments =
            runCatching { Json.parseToJsonElement(argumentsJson) as JsonArray }.getOrElse {
                logger.warn { "JavaScript interface call with malformed arguments dropped" }
                return
            }
        val method =
            target.javaClass.methods.firstOrNull {
                it.name == methodName && it.isAnnotationPresent(JavascriptInterface::class.java) &&
                    it.parameterCount == arguments.size
            } ?: run {
                logger.warn { "No @JavascriptInterface method $methodName/${arguments.size} on ${target.javaClass.name}" }
                return
            }
        val values = method.parameterTypes.mapIndexed { i, type -> coerce(arguments[i], type) }
        try {
            method.isAccessible = true
            method.invoke(target, *values.toTypedArray())
        } catch (t: Throwable) {
            logger.warn(t.cause ?: t) { "JavaScript interface method $methodName failed" }
        }
    }

    private fun coerce(
        value: JsonElement,
        type: Class<*>,
    ): Any? {
        if (value is JsonNull) {
            return when (type) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                else -> null
            }
        }
        if (value !is JsonPrimitive) {
            return if (type == String::class.java || type == Any::class.java) value.toString() else null
        }
        val text = value.content
        val number = text.toDoubleOrNull() ?: 0.0
        return when (type) {
            String::class.java, CharSequence::class.java, Any::class.java -> text
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> text == "true"
            Integer.TYPE, Integer::class.java -> number.toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> number.toLong()
            java.lang.Double.TYPE, java.lang.Double::class.java -> number
            java.lang.Float.TYPE, java.lang.Float::class.java -> number.toFloat()
            java.lang.Short.TYPE, java.lang.Short::class.java -> number.toInt().toShort()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> number.toInt().toByte()
            else -> null
        }
    }
}

internal class WebMessageDelegate(
    private val data: String,
) : org.chromium.support_lib_boundary.WebMessageBoundaryInterface {
    override fun getData(): String = data

    override fun getMessagePayload(): InvocationHandler = throw UnsupportedOperationException("WEB_MESSAGE_ARRAY_BUFFER")

    override fun getPorts(): Array<InvocationHandler> = emptyArray()

    override fun getSupportedFeatures(): Array<String> = emptyArray()
}

internal class ReplyProxyDelegate(
    private val post: (String) -> Unit,
) : org.chromium.support_lib_boundary.JsReplyProxyBoundaryInterface {
    override fun postMessage(message: String) = post(message)

    override fun executeJavaScript(
        script: String,
        callback: InvocationHandler,
    ): Unit = throw UnsupportedOperationException("JS_INJECTION_IN_FRAME_AND_WORLD")
}
