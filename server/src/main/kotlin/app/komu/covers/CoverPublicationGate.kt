/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serialises publishing a verified original into the cover cache with every
 * invalidation of that cache (metadata refresh, backup restore, library
 * removal, "clear cache"), per manga (ADR-0084 Review 1).
 *
 * A verification captures the manga's [Generation] before it reads the
 * cached thumbnail. Each invalidation bumps the generation under the same
 * lock that a publication holds while it checks the generation and moves
 * the file in, so a verification that started before a refresh can never
 * publish over the cover the refresh brought: the compare-and-publish fails
 * and the cover is verified again the next time it is shown.
 *
 * Locks and counters are striped (a fixed array, no per-title state), so a
 * refresh of another title in the same stripe can cause a spurious failure;
 * that only delays one upgrade to the next display.
 */
class CoverPublicationGate(
    stripes: Int = 256,
) {
    /** A manga's cache generation: whole-cache clears plus its stripe's invalidations. */
    data class Generation(
        val epoch: Long,
        val stripe: Long,
    )

    private val locks = Array(stripes) { ReentrantLock() }
    private val stripeGenerations = LongArray(stripes) // guarded by the stripe lock

    @Volatile
    private var epoch = 0L // written with every stripe lock held

    private fun index(mangaId: Int) = Math.floorMod(mangaId, locks.size)

    fun generation(mangaId: Int): Generation {
        val i = index(mangaId)
        return locks[i].withLock { Generation(epoch, stripeGenerations[i]) }
    }

    /** Runs [clear] (removing the manga's cached cover) and invalidates in-flight verifications of it. */
    fun <T> invalidate(
        mangaId: Int,
        clear: () -> T,
    ): T {
        val i = index(mangaId)
        return locks[i].withLock {
            stripeGenerations[i]++
            clear()
        }
    }

    /** Runs [clear] (removing a whole cover cache) and invalidates every in-flight verification. */
    fun <T> invalidateAll(clear: () -> T): T {
        locks.forEach { it.lock() }
        try {
            epoch++
            return clear()
        } finally {
            locks.reversed().forEach { it.unlock() }
        }
    }

    /**
     * Runs [publish] only while the manga is still at [expected]; returns
     * whether it ran. Nothing that waits on the network or the database may
     * run inside [publish].
     */
    fun publishIfCurrent(
        mangaId: Int,
        expected: Generation,
        publish: () -> Unit,
    ): Boolean {
        val i = index(mangaId)
        return locks[i].withLock {
            if (Generation(epoch, stripeGenerations[i]) != expected) return false
            publish()
            true
        }
    }
}
