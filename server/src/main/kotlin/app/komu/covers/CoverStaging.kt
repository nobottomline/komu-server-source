/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Where cover files are staged before an atomic rename into a cover cache
 * (ADR-0084 Review 5): `<parent of the cache>/.komu-cover-staging`, beside
 * the cache on the same volume (so publication stays an atomic rename) but
 * never inside it, so no cache clear (which deletes the cache directory)
 * ever removes a file a request is still writing. Names are unique per
 * request.
 *
 * Liveness is explicit (ADR-0084 Review 6): every staging file belongs to a
 * [Lease], whose name is registered in this process before the file exists
 * and unregistered only after the file is gone, when the request finishes
 * (published, discarded or failed). A sweep never deletes a registered
 * name, whatever its age; age only judges unregistered files, i.e. what a
 * previous process left behind. The lease also holds the file's descriptor
 * from creation, so its bytes stay readable even if something else unlinks
 * the name.
 */
object CoverStaging {
    const val DIRECTORY_NAME = ".komu-cover-staging"

    /** An unregistered staging file older than this is a previous process's orphan. */
    const val MAXIMUM_AGE_MILLIS = 60 * 60 * 1000L

    /** At most one age sweep per this interval. */
    private const val SWEEP_INTERVAL_MILLIS = 10 * 60 * 1000L

    private val lastSweep = AtomicLong(0)

    /** Absolute paths of the staging files of this process's running requests. */
    private val live: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The staging directory for files published into [cacheDirectory]. */
    fun directory(cacheDirectory: File): File {
        val absolute = cacheDirectory.absoluteFile
        return File(absolute.parentFile ?: absolute, DIRECTORY_NAME)
    }

    /** Lets the next [stage] run its age sweep (tests). */
    internal fun resetSweepSchedule() = lastSweep.set(0)

    /** Whether [file] is the staging file of a running request of this process. */
    fun isLive(file: File): Boolean = file.absolutePath in live

    /**
     * A staging file for [mangaId]'s cover in [cacheDirectory], registered
     * and opened for reading and writing before anything can see it. The
     * caller closes the lease when its request finishes.
     */
    fun stage(
        cacheDirectory: File,
        mangaId: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Lease {
        val directory = directory(cacheDirectory).apply { mkdirs() }
        val last = lastSweep.get()
        if (nowMillis - last >= SWEEP_INTERVAL_MILLIS && lastSweep.compareAndSet(last, nowMillis)) {
            sweep(directory, olderThanMillis = MAXIMUM_AGE_MILLIS, nowMillis = nowMillis)
        }
        val file = File(directory, "$mangaId.${UUID.randomUUID()}.komu-tmp")
        val key = file.absolutePath
        // Registered first: no sweep ever sees this name unregistered while it exists.
        live.add(key)
        try {
            val channel =
                FileChannel.open(
                    file.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            return Lease(file, key, channel)
        } catch (error: Throwable) {
            file.delete()
            live.remove(key)
            throw error
        }
    }

    /**
     * Start-up: no request of this process runs yet, so every unregistered
     * staged file of the caches' staging directories is an orphan.
     */
    fun sweepAtStartup(cacheDirectories: List<File>) {
        cacheDirectories.map(::directory).distinct().forEach { sweep(it, olderThanMillis = 0, nowMillis = Long.MAX_VALUE) }
    }

    /**
     * Deletes the regular files of [directory] that no running request
     * holds and that were last modified at least [olderThanMillis] before
     * [nowMillis]. A registered name is never deleted.
     */
    fun sweep(
        directory: File,
        olderThanMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && !isLive(it) && nowMillis - it.lastModified() >= olderThanMillis }
            .count { it.delete() }

    /**
     * One request's staging file and its descriptor, open from creation.
     * [close] removes the file (if it was not published) and then the
     * registration, and closes the descriptor unless [reader] handed it to
     * the request's answer.
     */
    class Lease internal constructor(
        val file: File,
        private val key: String,
        private val channel: FileChannel,
    ) : Closeable {
        private var handedOff = false

        /** Appends [bytes]. */
        fun write(
            bytes: ByteArray,
            length: Int = bytes.size,
        ) {
            val buffer = ByteBuffer.wrap(bytes, 0, length)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        /** Appends the rest of [input]. */
        fun append(input: InputStream) {
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) return
                write(chunk, read)
            }
        }

        /**
         * Everything written, read through the descriptor opened at
         * creation, so it stays readable whatever happened to the name. The
         * stream owns the descriptor from now on.
         */
        fun reader(): InputStream {
            channel.position(0)
            handedOff = true
            return Channels.newInputStream(channel).buffered()
        }

        /** Renames the staging file over [target], atomically where the volume can. */
        fun moveTo(target: File) {
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        override fun close() {
            try {
                if (!handedOff) {
                    try {
                        channel.close()
                    } catch (_: IOException) {
                        // Nothing to keep.
                    }
                }
                file.delete()
            } finally {
                live.remove(key)
            }
        }
    }
}
