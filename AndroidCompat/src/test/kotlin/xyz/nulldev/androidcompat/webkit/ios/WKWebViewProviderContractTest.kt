package xyz.nulldev.androidcompat.webkit.ios

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.komu.bridge.JsExecutor
import org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface
import org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface
import org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface
import org.chromium.support_lib_glue.SupportLibReflectionUtil
import xyz.nulldev.androidcompat.webkit.WebViewProviders
import xyz.nulldev.androidcompat.webkit.WebViewUnavailableException
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The provider's half of the `runWebView` contract against a scripted engine:
 * which thread each callback runs on, what is sent to the engine and when, and
 * how engine events reach the extension's clients.
 */
class WKWebViewProviderContractTest {
    /** Records commands; events are injected by the test and handed to the provider's pump. */
    class FakeEngine : WebEngine {
        data class Command(
            val handle: Long,
            val op: Int,
            val id: Long,
            val fields: List<String>,
        )

        @Volatile var availability = JsExecutor.AVAILABLE
        private var next = 100L
        val commands = LinkedBlockingQueue<Command>()
        val destroyed = CopyOnWriteArrayList<Long>()
        private val events = LinkedBlockingQueue<ByteArray>()

        override fun availability() = availability

        @Synchronized override fun create(): Long = ++next

        override fun destroy(handle: Long) {
            destroyed += handle
        }

        override fun submit(
            handle: Long,
            op: Int,
            id: Long,
            fields: List<String>,
        ) {
            commands += Command(handle, op, id, fields)
        }

        override fun nextEvents(timeoutMs: Int): ByteArray? = events.poll(minOf(timeoutMs, 200).toLong(), TimeUnit.MILLISECONDS)

        fun emit(
            handle: Long,
            kind: Int,
            id: Long = 0,
            vararg fields: String,
        ) {
            events += WebEngineFrames.encodeEvent(WebEngineEvent(handle, kind, id, fields.toList()))
        }

        fun await(op: Int): Command {
            while (true) {
                val c = commands.poll(5, TimeUnit.SECONDS) ?: error("no command $op")
                if (c.op == op) return c
            }
        }
    }

    private lateinit var engine: FakeEngine
    private lateinit var main: Handler
    private lateinit var mainLooper: Looper
    private val views = mutableListOf<WebView>()

    @BeforeTest
    fun setUp() {
        engine = FakeEngine()
        WebViewProviders.installIOSEngineForTesting(engine)
        WebView.setProviderFactory(WebViewProviders.factoryFor("ios"))
        val ready = CountDownLatch(1)
        val looper = AtomicReference<Looper>()
        Thread {
            Looper.prepare()
            looper.set(Looper.myLooper())
            ready.countDown()
            Looper.loop()
        }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        mainLooper = looper.get()
        main = Handler(mainLooper)
    }

    @AfterTest
    fun tearDown() {
        views.forEach { v -> onMain { v.destroy() } }
        mainLooper.quit()
        WebViewProviders.installIOSEngineForTesting(null)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        main.post {
            result.set(runCatching(block))
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "main looper did not run the block")
        return result.get().getOrThrow()
    }

    private fun newWebView(): WebView = onMain { WebView(Application()) }.also { views += it }

    private fun handleOf(view: WebView) = (view.webViewProvider as WKWebViewProvider).handle

    @Test
    fun pageEventsAndEvaluationResultsRunOnTheLooperWithTheirUrls() {
        val view = newWebView()
        val handle = handleOf(view)
        val events = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Boolean>()
        val finished = CountDownLatch(1)
        onMain {
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView,
                        url: String,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        threads += Looper.myLooper() == mainLooper
                        events += "started $url"
                    }

                    override fun onPageFinished(
                        view: WebView,
                        url: String,
                    ) {
                        threads += Looper.myLooper() == mainLooper
                        events += "finished $url"
                        view.evaluateJavascript("document.title") { value ->
                            threads += Looper.myLooper() == mainLooper
                            events += "eval $value"
                            finished.countDown()
                        }
                    }
                }
            view.settings.userAgentString = "UA/1"
            view.loadUrl("https://site.test/a", mapOf("Referer" to "https://site.test/"))
        }
        val load = engine.await(JsExecutor.OP_LOAD_URL)
        assertEquals("https://site.test/a", load.fields[0])
        assertEquals("Referer: https://site.test/", load.fields[1])
        assertEquals("UA/1", load.fields[2], "the session User-Agent travels with the load")
        engine.emit(handle, JsExecutor.EV_PAGE_STARTED, 0, "https://site.test/a")
        engine.emit(handle, JsExecutor.EV_PAGE_FINISHED, 0, "https://site.test/a")
        val evaluate = engine.await(JsExecutor.OP_EVALUATE)
        assertEquals(listOf("document.title"), evaluate.fields)
        engine.emit(handle, JsExecutor.EV_EVAL_RESULT, evaluate.id, "\"Title\"")
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("started https://site.test/a", "finished https://site.test/a", "eval \"Title\""), events)
        assertTrue(threads.all { it }, "every client callback ran on the WebView's looper")
    }

    @Test
    fun loadDataUsesTheSameUserAgentAndBaseUrl() {
        val view = newWebView()
        onMain {
            view.settings.userAgentString = "UA/2"
            view.loadDataWithBaseURL("https://site.test/base/", "<p>x</p>", "text/html", "utf-8", null)
        }
        val load = engine.await(JsExecutor.OP_LOAD_DATA)
        assertEquals(listOf("https://site.test/base/", "<p>x</p>", "text/html", "utf-8", "UA/2"), load.fields.take(5))
    }

    class Bridge {
        val calls = LinkedBlockingQueue<String>()

        @JavascriptInterface
        fun post(
            message: String,
            count: Int,
        ) {
            calls += "${Thread.currentThread().name}|$message|$count"
        }

        @Suppress("unused")
        fun secret(message: String) {
            calls += "secret $message"
        }
    }

    @Test
    fun onlyJavascriptInterfaceMethodsAreExposedAndTheyRunInTheBackground() {
        val view = newWebView()
        val handle = handleOf(view)
        val bridge = Bridge()
        onMain { view.addJavascriptInterface(bridge, "app") }
        val add = engine.await(JsExecutor.OP_ADD_INTERFACE)
        assertEquals(listOf("app", "[\"post\"]"), add.fields, "only annotated methods are offered to the page")
        engine.emit(handle, JsExecutor.EV_BRIDGE_CALL, 0, "app", "secret", "[\"x\"]")
        engine.emit(handle, JsExecutor.EV_BRIDGE_CALL, 0, "app", "post", "[\"hi\", 3]")
        val call = bridge.calls.poll(5, TimeUnit.SECONDS)!!
        assertTrue(call.endsWith("|hi|3"), call)
        assertTrue(call.startsWith("WKWebView-io"), "runs on a background thread: $call")
        assertEquals(null, bridge.calls.poll(300, TimeUnit.MILLISECONDS), "the unannotated method was not called")
    }

    @Test
    fun interceptionObservesInTheBackgroundAndBlocksOnlyHeldNavigations() {
        val view = newWebView()
        val handle = handleOf(view)
        val seen = LinkedBlockingQueue<String>()
        onMain {
            view.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        seen += "${Looper.myLooper() == mainLooper}|${request.method} ${request.url} main=${request.isForMainFrame}"
                        return if (request.url.toString().contains("blocked")) {
                            WebResourceResponse("text/plain", "utf-8", 403, "no", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        } else {
                            null
                        }
                    }
                }
        }
        assertEquals(listOf("1"), engine.await(JsExecutor.OP_SET_INTERCEPTION).fields)
        engine.emit(handle, JsExecutor.EV_REQUEST, 0, "https://site.test/api", "POST", "0", "0", "")
        engine.emit(handle, JsExecutor.EV_REQUEST, 7, "https://site.test/blocked-frame", "GET", "0", "0", "")
        // Like Android, requests are intercepted concurrently: no order between them.
        assertEquals(
            setOf("false|POST https://site.test/api main=false", "false|GET https://site.test/blocked-frame main=false"),
            setOf(seen.poll(5, TimeUnit.SECONDS), seen.poll(5, TimeUnit.SECONDS)),
        )
        val decision = engine.await(JsExecutor.OP_DECIDE)
        assertEquals(7L, decision.id)
        assertEquals(listOf("0"), decision.fields, "a response blocks the held navigation")
    }

    @Test
    fun aDefaultClientDoesNotArmInterceptionAndHeldNavigationsAreAllowed() {
        val view = newWebView()
        val handle = handleOf(view)
        onMain { view.webViewClient = WebViewClient() }
        engine.emit(handle, JsExecutor.EV_REQUEST, 9, "https://site.test/frame", "GET", "0", "0", "")
        val decision = engine.await(JsExecutor.OP_DECIDE)
        assertEquals(9L, decision.id)
        assertEquals(listOf("1"), decision.fields)
        assertTrue(engine.commands.none { it.op == JsExecutor.OP_SET_INTERCEPTION })
    }

    @Test
    fun processLossReachesTheClientOnTheLooperAndLaterCommandsAreDropped() {
        val view = newWebView()
        val handle = handleOf(view)
        val gone = LinkedBlockingQueue<String>()
        onMain {
            view.webViewClient =
                object : WebViewClient() {
                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        gone += "${Looper.myLooper() == mainLooper}|${detail.didCrash()}"
                        return true
                    }
                }
        }
        engine.emit(handle, JsExecutor.EV_PROCESS_GONE, 0, "0", "background")
        assertEquals("true|false", gone.poll(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        engine.commands.clear()
        onMain { view.evaluateJavascript("1", null) }
        assertEquals(null, engine.commands.poll(300, TimeUnit.MILLISECONDS), "nothing is sent to a dead session")
    }

    @Test
    fun loadErrorsReachTheNewCallbackWithAndroidCodes() {
        val view = newWebView()
        val handle = handleOf(view)
        val errors = LinkedBlockingQueue<String>()
        onMain {
            view.webViewClient =
                object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        errors += "${request.url} ${request.isForMainFrame} ${error.errorCode} ${error.description}"
                    }
                }
        }
        engine.emit(handle, JsExecutor.EV_LOAD_ERROR, 0, "https://nx.test/", "-1003", "host not found", "1")
        assertEquals("https://nx.test/ true ${WebViewClient.ERROR_HOST_LOOKUP} host not found", errors.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun sessionsBeyondTheLimitWaitWithoutBlockingAndStartWhenOneEnds() {
        val views = (1..WKWebViewSessions.maxActive + 1).map { newWebView() }
        onMain { views.forEach { it.loadUrl("https://site.test/${handleOf(it)}") } }
        val loads = (1..WKWebViewSessions.maxActive).map { engine.await(JsExecutor.OP_LOAD_URL) }
        assertEquals(WKWebViewSessions.maxActive, loads.size)
        assertEquals(null, engine.commands.poll(300, TimeUnit.MILLISECONDS), "the extra session waits")
        onMain { views.first().destroy() }
        val late = engine.await(JsExecutor.OP_LOAD_URL)
        assertEquals(handleOf(views.last()), late.handle, "the waiting session starts once a slot frees")
    }

    @Test
    fun backgroundWithoutLeaseFailsFastWithATypedError() {
        engine.availability = JsExecutor.REQUIRES_FOREGROUND
        val failure = runCatching { newWebView() }.exceptionOrNull()
        val typed = assertIs<WebViewUnavailableException>(failure)
        assertEquals(WebViewUnavailableException.Reason.REQUIRES_FOREGROUND, typed.reason)
    }

    @Test
    fun aProcessWithoutTheEngineFailsFastWithATypedError() {
        WebViewProviders.installIOSEngineForTesting(null)
        val typed = assertIs<WebViewUnavailableException>(runCatching { newWebView() }.exceptionOrNull())
        assertEquals(WebViewUnavailableException.Reason.NATIVE_BRIDGE_MISSING, typed.reason)
    }

    @Test
    fun theBoundaryGlueOffersDocumentStartScriptsAndMessageListeners() {
        val view = newWebView()
        // What Keiyoushi's WebViewGlueBridge does, through the same reflection entry point.
        val loader = onMain { WebView.getWebViewClassLoader() }
        val util = Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil", false, loader)
        val factoryHandler = util.getDeclaredMethod("createWebViewProviderFactory").invoke(null) as InvocationHandler
        assertEquals(SupportLibReflectionUtil::class.java.classLoader, util.classLoader)
        val factory =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WebViewProviderFactoryBoundaryInterface::class.java), factoryHandler)
                as WebViewProviderFactoryBoundaryInterface
        val features = factory.supportedFeatures.toSet()
        assertTrue("DOCUMENT_START_SCRIPT:1" in features && "WEB_MESSAGE_LISTENER" in features, "$features")
        assertFalse("JS_INJECTION_IN_FRAME_AND_WORLD" in features)
        val provider =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(WebViewProviderBoundaryInterface::class.java),
                factory.createWebView(view),
            )
                as WebViewProviderBoundaryInterface
        val script = onMain { provider.addDocumentStartJavaScript("window.a = 1", arrayOf("https://site.test")) }
        val added = engine.await(JsExecutor.OP_ADD_DOC_START)
        assertEquals(listOf("window.a = 1", "[\"https://site.test\"]"), added.fields)
        val handle =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ScriptHandlerBoundaryInterface::class.java), script)
                as ScriptHandlerBoundaryInterface
        onMain { handle.remove() }
        assertEquals(added.id, engine.await(JsExecutor.OP_REMOVE_DOC_START).id)
        val invalid = runCatching { onMain { provider.addDocumentStartJavaScript("x", arrayOf("site.test")) } }.exceptionOrNull()
        assertIs<IllegalArgumentException>(invalid, "a rule without a scheme is rejected, as on Android")
    }

    @Test
    fun webMessagesReachTheListenerOnTheLooperAndRepliesGoBack() {
        val view = newWebView()
        val handle = handleOf(view)
        val received = LinkedBlockingQueue<String>()
        val listener =
            InvocationHandler { _, method, args ->
                if (method.name == "onPostMessage") {
                    val message =
                        Proxy.newProxyInstance(
                            javaClass.classLoader,
                            arrayOf(org.chromium.support_lib_boundary.WebMessageBoundaryInterface::class.java),
                            args!![1] as InvocationHandler,
                        ) as org.chromium.support_lib_boundary.WebMessageBoundaryInterface
                    val reply =
                        Proxy.newProxyInstance(
                            javaClass.classLoader,
                            arrayOf(org.chromium.support_lib_boundary.JsReplyProxyBoundaryInterface::class.java),
                            args[4] as InvocationHandler,
                        ) as org.chromium.support_lib_boundary.JsReplyProxyBoundaryInterface
                    received += "${Looper.myLooper() == mainLooper}|${message.data}|${args[2]}|${args[3]}"
                    reply.postMessage("pong")
                }
                null
            }
        onMain { (view.webViewProvider as WKWebViewProvider).addWebMessageListener("bridge", arrayOf("https://site.test"), listener) }
        assertEquals(listOf("bridge", "[\"https://site.test\"]"), engine.await(JsExecutor.OP_ADD_LISTENER).fields)
        engine.emit(handle, JsExecutor.EV_WEB_MESSAGE, 42, "bridge", "ping", "https://site.test", "1")
        assertEquals("true|ping|https://site.test|true", received.poll(5, TimeUnit.SECONDS))
        val reply = engine.await(JsExecutor.OP_POST_REPLY)
        assertEquals(42L, reply.id)
        assertEquals(listOf("pong"), reply.fields)
    }

    @Test
    fun destroyEndsTheSessionAndDropsPendingCallbacks() {
        val view = newWebView()
        val handle = handleOf(view)
        val called = CountDownLatch(1)
        onMain { view.evaluateJavascript("1") { called.countDown() } }
        val evaluate = engine.await(JsExecutor.OP_EVALUATE)
        onMain { view.destroy() }
        engine.emit(handle, JsExecutor.EV_EVAL_RESULT, evaluate.id, "1")
        assertFalse(called.await(300, TimeUnit.MILLISECONDS), "no callback after destroy")
        assertTrue(handle in engine.destroyed)
        assertNotEquals(0L, handle)
    }

    @Test
    fun cookieRulesFollowTheTargetSite() {
        assertTrue(CookieLines.domainMatches("www.site.test", "site.test"))
        assertTrue(CookieLines.domainMatches("site.test", "site.test"))
        assertFalse(CookieLines.domainMatches("site.test", "ads.test"))
        assertFalse(CookieLines.domainMatches("evilsite.test", "site.test"))
    }

    @Test
    fun engineFramesRoundTrip() {
        val event = WebEngineEvent(5, JsExecutor.EV_COOKIES, 9, listOf("https://a.test/", "a=1; Domain=a.test", "ü \u0000 😀"))
        assertEquals(
            listOf(event, event),
            WebEngineFrames.decodeEvents(WebEngineFrames.encodeEvent(event) + WebEngineFrames.encodeEvent(event)),
        )
        assertTrue(runCatching { WebEngineFrames.decodeEvents(WebEngineFrames.encodeEvent(event).copyOf(30)) }.isFailure)
    }
}
