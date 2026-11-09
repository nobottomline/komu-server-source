package xyz.nulldev.androidcompat.webkit

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Komu P0 (R1): on the iOS host CEF never starts. An extension's
 * `runWebView` constructs its WebView on the main looper; with KCEF's provider
 * that construction waited for CEF forever and stalled the looper for every
 * later WebView. These tests run the construction on a looper thread, as
 * Keiyoushi's `withContext(Dispatchers.Main)` does.
 */
class WebViewFailFastTest {
    private val loopers = mutableListOf<Looper>()

    @BeforeTest
    fun koin() {
        // KcefWebViewProvider resolves its browser-init hook from Koin when the class loads.
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(
                    module {
                        single<KcefWebViewProvider.InitBrowserHandler> {
                            object : KcefWebViewProvider.InitBrowserHandler {
                                override fun init(provider: KcefWebViewProvider) = Unit
                            }
                        }
                    },
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        loopers.forEach { it.quit() }
        CefHelper.cefApp.value = Result.success(null)
    }

    /** A looper thread like the server's main looper; daemon so a stuck control cannot hold the test JVM. */
    private fun looperThread(): Handler {
        val ready = CountDownLatch(1)
        val looper = AtomicReference<Looper>()
        Thread {
            Looper.prepare()
            looper.set(Looper.myLooper())
            ready.countDown()
            Looper.loop()
        }.apply { isDaemon = true }.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        loopers += looper.get()
        return Handler(looper.get())
    }

    /** Constructs a WebView on [handler]'s looper; the outcome, or null if it did not finish in [millis]. */
    private fun construct(
        handler: Handler,
        millis: Long,
    ): Result<WebView>? {
        val outcome = AtomicReference<Result<WebView>>()
        val done = CountDownLatch(1)
        handler.post {
            outcome.set(runCatching { WebView(Application()) })
            done.countDown()
        }
        return if (done.await(millis, TimeUnit.MILLISECONDS)) outcome.get() else null
    }

    private fun looperRuns(handler: Handler): Boolean {
        val ran = CountDownLatch(1)
        handler.post { ran.countDown() }
        return ran.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun iosHostWithoutTheEngineFailsFastWithATypedErrorAndKeepsTheLooperRunning() {
        // This JVM does not link the app's WKWebView engine.
        WebViewProviders.installIOSEngineForTesting(null)
        WebView.setProviderFactory(WebViewProviders.factoryFor("ios"))
        val handler = looperThread()
        val began = System.nanoTime()
        val first = construct(handler, 2_000)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        val failure = assertIs<WebViewUnavailableException>(first?.exceptionOrNull(), "a typed failure, not a hang")
        assertEquals(WebViewUnavailableException.Reason.NATIVE_BRIDGE_MISSING, failure.reason)
        assertTrue(elapsedMs < 1_000, "failed after $elapsedMs ms")
        assertTrue(looperRuns(handler), "the looper still runs posted work")
        val second = construct(handler, 2_000)
        assertIs<WebViewUnavailableException>(second?.exceptionOrNull(), "every later WebView fails the same way")
    }

    @Test
    fun desktopHostKeepsTheKcefProvider() {
        // Selection only: constructing it would start JCEF.
        val factory = WebViewProviders.factoryFor(null)
        assertFalse(WebViewProviders.isIOS(null))
        assertTrue(WebViewProviders.isIOS("ios"))
        assertTrue(factory.javaClass != WebViewProviders.factoryFor("ios").javaClass)
    }

    @Test
    fun kcefFailsFastOnceCefIsMarkedUnavailable() {
        // ServerSetup marks CEF failed on hosts that cannot start it.
        CefHelper.cefApp.value = Result.failure(CefHelper.CefException("CEF is not available on this platform"))
        WebView.setProviderFactory { view: WebView -> KcefWebViewProvider(view) }
        val handler = looperThread()
        val outcome = construct(handler, 3_000)
        assertIs<CefHelper.CefException>(outcome?.exceptionOrNull(), "KCEF reports the failure instead of waiting")
        assertTrue(looperRuns(handler))
    }

    /**
     * Red control: the state the iOS host was in before. CEF is neither started nor
     * failed, so KCEF's provider waits forever on the looper thread.
     */
    @Test
    fun controlKcefWithCefNeverStartedBlocksTheLooper() {
        CefHelper.cefApp.value = Result.success(null)
        WebView.setProviderFactory { view: WebView -> KcefWebViewProvider(view) }
        val handler = looperThread()
        assertEquals(null, construct(handler, 2_000), "construction is still waiting after 2 s")
        assertFalse(looperRuns(handler), "and the looper runs nothing else")
    }
}
