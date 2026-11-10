package xyz.nulldev.androidcompat.webkit.ios

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Komu: process-wide state of the iOS WebView engine.
 *
 * - **Admission.** At most [maxActive] sessions run at once (each WKWebView has its
 *   own WebContent process). A further session waits, without blocking any thread:
 *   its commands are buffered until a running session ends (its loads are declared
 *   to the engine at once, so its egress binding exists while it waits). The
 *   extension's own timeout bounds the wait. A session the engine ended while it
 *   waited never runs.
 * - **Event pump.** One daemon thread drains the engine's event queue and hands
 *   each event to its provider, which posts page events to the WebView's looper and
 *   runs request interception and JavaScript-interface calls on [background].
 *   The thread exits when no session is open.
 */
internal object WKWebViewSessions {
    val maxActive: Int =
        System.getProperty("komu.webview.maxSessions")?.toIntOrNull()?.coerceIn(1, 8) ?: 3

    private const val EVENT_WAIT_MS = 15_000

    private val lock = Any()
    private var engine: WebEngine? = null
    private val providers = HashMap<Long, WKWebViewProvider>()
    private val waiting = ArrayDeque<WKWebViewProvider>()
    private val granted = HashSet<Long>()
    private var pumpFor: WebEngine? = null

    /** Interception and JavaScript-interface calls (Android runs both off the UI thread). */
    val background: ExecutorService =
        ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), daemonThreads("WKWebView-io")).apply {
            allowCoreThreadTimeOut(true)
        }

    fun install(engine: WebEngine) {
        synchronized(lock) {
            if (this.engine === engine) return
            check(providers.isEmpty()) { "WebView engine replaced while sessions are open" }
            this.engine = engine
        }
    }

    /** Registers an open session and grants it a slot now or when one frees up. */
    fun open(provider: WKWebViewProvider) {
        val grantNow: Boolean
        synchronized(lock) {
            providers[provider.handle] = provider
            grantNow = granted.size < maxActive
            if (grantNow) granted += provider.handle else waiting.addLast(provider)
            startPumpLocked()
        }
        if (grantNow && !provider.activate()) release(provider)
    }

    /** The session ended: frees its slot for the next waiting session. */
    fun close(provider: WKWebViewProvider) {
        synchronized(lock) { providers.remove(provider.handle) }
        release(provider)
    }

    /**
     * The engine ended the session (process gone, egress binding revoked, execution
     * lost): a waiting session leaves the queue and never runs; a running one keeps
     * its slot until the extension destroys it.
     */
    fun ended(provider: WKWebViewProvider) {
        synchronized(lock) { waiting.remove(provider) }
    }

    /** Frees [provider]'s slot (if it held one) and hands it to the next waiting session that can still run. */
    private fun release(provider: WKWebViewProvider) {
        var freed: WKWebViewProvider? = provider
        while (freed != null) {
            val next: WKWebViewProvider?
            synchronized(lock) {
                waiting.remove(freed)
                if (!granted.remove(freed.handle)) return
                next = waiting.removeFirstOrNull()?.also { granted += it.handle }
            }
            freed = next?.takeUnless { it.activate() }
        }
    }

    fun openSessions(): Int = synchronized(lock) { providers.size }

    fun activeSessions(): Int = synchronized(lock) { granted.size }

    private fun startPumpLocked() {
        val engine = engine ?: return
        if (pumpFor === engine) return
        pumpFor = engine
        Thread({ pump(engine) }, "WKWebView-events").apply { isDaemon = true }.start()
    }

    private fun pump(engine: WebEngine) {
        while (true) {
            val batch =
                try {
                    engine.nextEvents(EVENT_WAIT_MS)
                } catch (t: Throwable) {
                    logger.error(t) { "WebView engine event wait failed" }
                    Thread.sleep(1_000)
                    null
                }
            if (batch == null) {
                synchronized(lock) {
                    if (pumpFor !== engine) return
                    if (providers.isEmpty()) {
                        pumpFor = null
                        return
                    }
                }
                continue
            }
            val events =
                try {
                    WebEngineFrames.decodeEvents(batch)
                } catch (e: IllegalArgumentException) {
                    logger.error(e) { "Malformed WebView engine events dropped" }
                    continue
                }
            for (event in events) {
                val provider = synchronized(lock) { providers[event.handle] } ?: continue
                try {
                    provider.onEngineEvent(event)
                } catch (t: Throwable) {
                    logger.error(t) { "WebView engine event ${event.kind} failed" }
                }
            }
        }
    }

    private fun daemonThreads(prefix: String): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${count.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}

/** Runs tasks one at a time, in order, on [executor] (Android's per-WebView JavaBridge thread). */
internal class SerialExecutor(
    private val executor: ExecutorService,
) {
    private val tasks = ArrayDeque<Runnable>()
    private var running = false

    fun execute(task: Runnable) {
        synchronized(tasks) {
            tasks.addLast(task)
            if (running) return
            running = true
        }
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val task =
                synchronized(tasks) {
                    tasks.removeFirstOrNull() ?: run {
                        running = false
                        null
                    }
                } ?: return
            try {
                task.run()
            } catch (t: Throwable) {
                logger.warn(t) { "WebView background task failed" }
            }
        }
    }
}
