/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Where cover files are staged before an atomic rename into a cover cache
 * (ADR-0084 Review 5): `<parent of the cache>/.komu-cover-staging`, beside
 * the cache on the same volume (so publication stays an atomic rename) but
 * never inside it, so no cache clear (which deletes the cache directory)
 * ever removes a file a request is still writing. Names are unique per
 * request. Orphans (a process killed mid-request) are removed at start-up
 * and, while running, once they are older than [MAXIMUM_AGE_MILLIS].
 */
object CoverStaging {
    const val DIRECTORY_NAME = ".komu-cover-staging"

    /** No request stages a cover for this long; anything older is an orphan. */
    const val MAXIMUM_AGE_MILLIS = 60 * 60 * 1000L

    /** At most one age sweep per directory per this interval. */
    private const val SWEEP_INTERVAL_MILLIS = 10 * 60 * 1000L

    private val lastSweep = AtomicLong(0)

    /** The staging directory for files published into [cacheDirectory]. */
    fun directory(cacheDirectory: File): File {
        val absolute = cacheDirectory.absoluteFile
        return File(absolute.parentFile ?: absolute, DIRECTORY_NAME)
    }

    /** A new, unique staging file for [mangaId]'s cover in [cacheDirectory]. */
    fun newFile(
        cacheDirectory: File,
        mangaId: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): File {
        val directory = directory(cacheDirectory).apply { mkdirs() }
        val last = lastSweep.get()
        if (nowMillis - last >= SWEEP_INTERVAL_MILLIS && lastSweep.compareAndSet(last, nowMillis)) {
            sweep(directory, olderThanMillis = MAXIMUM_AGE_MILLIS, nowMillis = nowMillis)
        }
        return File(directory, "$mangaId.${UUID.randomUUID()}.komu-tmp")
    }

    /**
     * Start-up: no request is running yet, so every staged file of the
     * caches' staging directories is an orphan.
     */
    fun sweepAtStartup(cacheDirectories: List<File>) {
        cacheDirectories.map(::directory).distinct().forEach { sweep(it, olderThanMillis = 0, nowMillis = Long.MAX_VALUE) }
    }

    /** Deletes the regular files of [directory] last modified more than [olderThanMillis] before [nowMillis]. */
    fun sweep(
        directory: File,
        olderThanMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && nowMillis - it.lastModified() >= olderThanMillis }
            .count { it.delete() }
}
