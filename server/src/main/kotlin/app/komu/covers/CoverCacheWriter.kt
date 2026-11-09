/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import libcore.net.MimeUtils
import okhttp3.Response
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The only writer of cover-cache files (ADR-0084 Review 2): the upstream
 * thumbnail fill, its refill with an accepted original and the library
 * thumbnail download all finish through [save], under the manga's
 * [CoverPublicationGate] stripe and only while the generation captured
 * before the fetch began is unchanged. A fetch that a refresh overtook
 * (it read the old cover URL, then the cache was cleared) serves its bytes
 * to its own request and caches nothing, so it can never overwrite the
 * newer cover. `CoverCacheWriterArchitectureTest` fails on any other write
 * into the cover-cache directories.
 */
object CoverCacheWriter {
    /**
     * Upstream `ImageResponse.getImageResponse` for a cover: the cached file
     * when there is one, otherwise [fetcher]'s answer saved through [save].
     */
    suspend fun cachedOrFetched(
        gate: CoverPublicationGate,
        mangaId: Int,
        generation: CoverPublicationGate.Generation,
        saveDir: String,
        afterLookup: (File) -> Unit = {},
        fetcher: suspend () -> Response,
    ): Pair<InputStream, String> {
        File(saveDir).mkdirs()
        openCached(gate, mangaId, saveDir, afterLookup)?.let { return it }
        fetcher().use { response ->
            if (response.code != 200) throw Exception("request error! ${response.code}")
            return save(gate, mangaId, generation, File(saveDir), response.body.byteStream(), response.header("Content-Type")).served
        }
    }

    /**
     * The cached cover of [mangaId] in [directory], found and opened under
     * the manga's gate stripe, so a clear (which takes the same lock) cannot
     * remove it in between; an open stream keeps the file's bytes after a
     * later clear. `null` when there is none or it vanished anyway: the
     * caller fetches it, so a missing file never reaches the client
     * (ADR-0084 Review 4). [afterLookup] is a test seam between the lookup
     * and the open.
     */
    fun openCached(
        gate: CoverPublicationGate,
        mangaId: Int,
        directory: String,
        afterLookup: (File) -> Unit = {},
    ): Pair<InputStream, String>? =
        gate.withLock(mangaId) {
            val found =
                ImageResponse
                    .findFileNameStartingWith(directory, mangaId.toString())
                    ?.takeUnless { it.endsWith(".tmp") }
                    ?: return@withLock null
            afterLookup(File(found))
            try {
                ImageResponse.getCachedImageResponse(found, "$directory/$mangaId")
            } catch (_: FileNotFoundException) {
                null
            }
        }

    /**
     * What [save] did: the stream to serve to the request that fetched the
     * image and its MIME type (the cached file when it was written, the
     * fetched bytes otherwise), and whether the cache now holds them.
     */
    class Saved(
        val stream: InputStream,
        val mimeType: String,
        val cached: Boolean,
    ) {
        val served: Pair<InputStream, String> get() = stream to mimeType
    }

    /**
     * Writes [image] as `<directory>/<mangaId>.<type>` when the manga is
     * still at [generation]; the stream to serve and its MIME type either
     * way (ADR-0084 Review 3: a caller never looks the file up again, it may
     * not exist). The name follows the bytes (upstream `saveImage`), so a
     * 2418 reader that picks the type by extension stays right.
     */
    fun save(
        gate: CoverPublicationGate,
        mangaId: Int,
        generation: CoverPublicationGate.Generation,
        directory: File,
        image: InputStream,
        mimeType: String?,
    ): Saved {
        directory.mkdirs()
        // Hidden and unique: never found by a reader looking for "<id>.",
        // never clobbered by a concurrent fetch of the same cover.
        val staged = File(directory, ".$mangaId.${UUID.randomUUID()}.komu-tmp")
        try {
            image.use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
            val declared = mimeType?.takeIf { it.startsWith("image/") }?.lowercase()
            val type = ImageUtil.findImageType { staged.inputStream() } ?: ImageUtil.ImageType.entries.find { it.mime == declared }
            val extension = type?.extension ?: declared?.let { MimeUtils.guessExtensionFromMimeType(it) } ?: "jpg"
            val mime = type?.mime ?: declared ?: "image/jpeg"
            val target = File(directory, "$mangaId.$extension")
            var opened: InputStream? = null
            val cached =
                gate.publishIfCurrent(mangaId, generation) {
                    try {
                        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    // Opened under the lock: a clear right after cannot pull the file away.
                    opened = FileInputStream(target).buffered()
                }
            return Saved(opened ?: ByteArrayInputStream(staged.readBytes()), mime, cached)
        } finally {
            staged.delete()
        }
    }
}
