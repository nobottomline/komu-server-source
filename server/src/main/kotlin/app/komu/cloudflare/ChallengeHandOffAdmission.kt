package app.komu.cloudflare

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import okhttp3.Call
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform
import suwayomi.tachidesk.server.serverConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Admission of Cloudflare hand-off requests (Komu review 4).
 *
 * On iOS the FlareSolverr-compatible endpoint is a loopback listener of the
 * app. When the app loses or replaces that listener it must be sure the
 * server sends nothing (cookies, session token) to the old port before the
 * port is released and another process may bind it. Upstream checks
 * `flareSolverrEnabled` once and may post later, after waiting for its mutex.
 *
 * Here a request reads the address and session and registers its call under
 * one lock, only while admission is open; the host's switch-off closes
 * admission under the same lock, cancels every registered call and waits
 * until they finished, so when [closeAndDrain] returns true no request can
 * reach the old address any more. The host opens admission again only after
 * pointing the server at its new listener.
 *
 * Desktop (no host): admission follows `flareSolverrEnabled` as upstream.
 */
object ChallengeHandOffAdmission {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var open = false
    private val inFlight = LinkedHashSet<Call>()

    /** True when the iOS host owns admission; tests replace it. */
    @Volatile
    internal var hostManaged = EmbeddedRuntimePlatform.isIOS()

    internal class Settings(
        val enabled: Boolean,
        val url: String,
        val session: String,
    )

    /** The server's hand-off settings; tests replace it. */
    @Volatile
    internal var settings: () -> Settings = {
        Settings(
            serverConfig.flareSolverrEnabled.value,
            serverConfig.flareSolverrUrl.value,
            serverConfig.flareSolverrSessionName.value,
        )
    }

    @JvmStatic
    fun open() {
        lock.withLock { open = true }
    }

    /**
     * Closes admission, cancels the admitted calls and waits at most
     * [timeoutMillis] for them to finish; true when none is left.
     */
    @JvmStatic
    fun closeAndDrain(timeoutMillis: Long): Boolean =
        lock.withLock {
            open = false
            inFlight.toList().forEach(Call::cancel)
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (inFlight.isNotEmpty()) {
                if (remaining <= 0L) return@withLock false
                remaining = drained.awaitNanos(remaining)
            }
            true
        }

    /** The number of admitted calls (tests, diagnostics). */
    fun inFlightCount(): Int = lock.withLock { inFlight.size }

    /**
     * Admits one hand-off request: [newCall] builds the call for the current
     * address and session under the admission lock; [use] runs it. Throws
     * when the hand-off is off.
     */
    suspend fun <T> send(
        newCall: (url: String, session: String) -> Call,
        use: suspend (Call) -> T,
    ): T {
        val call =
            lock.withLock {
                val current = settings()
                if ((hostManaged && !open) || !current.enabled) {
                    throw IOException("Cloudflare bypass currently disabled")
                }
                newCall(current.url, current.session).also { inFlight += it }
            }
        try {
            return use(call)
        } finally {
            lock.withLock {
                inFlight -= call
                drained.signalAll()
            }
        }
    }
}
