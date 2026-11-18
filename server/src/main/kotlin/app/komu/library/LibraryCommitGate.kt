package app.komu.library

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import app.komu.bridge.LibraryCommit
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform
import kotlin.concurrent.thread

/**
 * Komu's library commit protocol (Komu docs/problems/
 * server-migration-interrupted-relaunch-2026-11-17.md, review 4).
 *
 * The iOS host starts a launch that may migrate on a copy of its library and
 * makes the copy the library only after this server reported that the
 * migrations are durable. Upstream migrations are not crash-safe and H2
 * keeps recent changes in memory, so HTTP readiness alone proves nothing:
 *
 * 1. after the migrations and the extension-activation recovery the server
 *    runs `CHECKPOINT SYNC` and reports [publishPrepared];
 * 2. the host syncs the library, flips its pointer and reports back;
 * 3. only then does the work run that changes data every library shares or
 *    that must outlive a discarded copy (automatic backups and their purge,
 *    the download queue, scheduled library updates, sync): [afterCommit].
 *
 * Desktop (no `komu.platform=ios`): no host, both steps are immediate, so
 * upstream behaviour is unchanged.
 */
object LibraryCommitGate {
    interface Host {
        fun prepared()

        fun committed(): Boolean
    }

    private object NativeHost : Host {
        override fun prepared() = LibraryCommit.libraryPrepared()

        override fun committed(): Boolean = LibraryCommit.libraryCommitted()
    }

    private val logger = KotlinLogging.logger {}

    /** Null on desktop; tests replace it. */
    @Volatile
    internal var host: Host? = if (EmbeddedRuntimePlatform.isIOS()) NativeHost else null

    internal var pollMillis = 250L

    /**
     * Makes the database durable and reports it to the host. Throws when the
     * checkpoint or the report fails: the caller must not go on as if the
     * library could be committed.
     */
    fun publishPrepared(checkpoint: () -> Unit = ::checkpointSync) {
        val host = host ?: return
        checkpoint()
        host.prepared()
        logger.info { "Library prepared: migrations checkpointed and synced" }
    }

    /**
     * Runs [block] once the host committed this launch's library (at once on
     * desktop), on a daemon thread when it has to wait.
     */
    fun afterCommit(
        name: String,
        block: () -> Unit,
    ) {
        val host = host
        if (host == null) {
            block()
            return
        }
        thread(name = "komu-library-commit-$name", isDaemon = true) {
            while (!host.committed()) Thread.sleep(pollMillis)
            logger.info { "Library committed: starting $name" }
            block()
        }
    }

    private fun checkpointSync() {
        transaction { exec("CHECKPOINT SYNC") }
    }
}
