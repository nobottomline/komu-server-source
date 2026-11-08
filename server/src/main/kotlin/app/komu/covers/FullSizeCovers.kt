/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.util.network.await
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

/**
 * Production wiring of [FullSizeCoverService]: the server's manga table,
 * source registry, cover caches and each source's own OkHttp client.
 * The two call sites in upstream code are `Manga.fetchHttpSourceMangaThumbnail`
 * ([originalResponse]) and the thumbnail endpoint ([serveState]).
 */
object FullSizeCovers {
    private val applicationDirs: ApplicationDirs by injectLazy()

    val service: FullSizeCoverService by lazy {
        val root = File(applicationDirs.dataRoot, "komu-covers")
        FullSizeCoverService(
            preferences = CoverPreferences(File(root, "preferences.properties")),
            store = CoverVerdictStore(File(root, "verdicts")),
            inspector = { CoverImageInspector.platform },
            http = SourceCoverHttp,
            catalog = ServerCoverCatalog(applicationDirs),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    /**
     * The accepted original for [thumbnailUrl] as a response the thumbnail
     * cache can store, or `null` to fetch the thumbnail as before.
     */
    suspend fun originalResponse(
        source: HttpSource,
        thumbnailUrl: String,
    ): Response? {
        val fetched = service.acceptedOriginal(source.id, source.baseUrl, thumbnailUrl) ?: return null
        val bytes = fetched.bytes ?: return null
        return Response
            .Builder()
            .request(Request.Builder().url(thumbnailUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(fetched.contentType?.toMediaTypeOrNull()))
            .build()
    }

    /** See [FullSizeCoverService.serveState]. */
    suspend fun serveState(mangaId: Int): CoverServeState = runCatching { service.serveState(mangaId) }.getOrDefault(CoverServeState.NOT_APPLICABLE)
}

/** Each source's own client and headers: its rate limits, Cloudflare handling and referer. */
internal object SourceCoverHttp : CoverHttp {
    override suspend fun get(
        sourceId: Long,
        url: String,
        maximumBytes: Int,
    ): CoverFetch {
        val source = GetSource.getSourceOrNull(sourceId) as? HttpSource ?: throw IOException("Source $sourceId is unavailable")
        source.client.newCall(GET(url, source.headers, cache = CacheControl.FORCE_NETWORK)).await().use { response ->
            if (!response.isSuccessful) return CoverFetch(response.code)
            val body = response.body
            if (body.contentLength() > maximumBytes) return CoverFetch(413)
            val bytes = body.byteStream().use { input -> input.readNBytes(maximumBytes + 1) }
            if (bytes.size > maximumBytes) return CoverFetch(413)
            return CoverFetch(response.code, bytes, response.header("Content-Type"))
        }
    }
}

internal class ServerCoverCatalog(
    private val applicationDirs: ApplicationDirs,
) : CoverCatalog {
    override suspend fun subject(mangaId: Int): CoverSubject? {
        val row = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull() } ?: return null
        val thumbnailUrl = row[MangaTable.thumbnail_url]?.takeIf { it.startsWith("http") } ?: return null
        val sourceId = row[MangaTable.sourceReference]
        if (sourceId == LocalSource.ID) return null
        val source = GetSource.getSourceOrNull(sourceId) as? HttpSource ?: return null
        return CoverSubject(mangaId, sourceId, runCatching { source.baseUrl }.getOrNull(), thumbnailUrl)
    }

    override fun servedCover(mangaId: Int): File? {
        val inLibrary = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull()?.get(MangaTable.inLibrary) } ?: false
        val root = if (inLibrary) applicationDirs.thumbnailDownloadsRoot else applicationDirs.tempThumbnailCacheRoot
        return find(root, mangaId)
    }

    override fun cachedCovers(mangaId: Int): List<File> =
        listOfNotNull(find(applicationDirs.tempThumbnailCacheRoot, mangaId), find(applicationDirs.thumbnailDownloadsRoot, mangaId))

    private fun find(
        root: String,
        mangaId: Int,
    ): File? =
        ImageResponse
            .findFileNameStartingWith(root, mangaId.toString())
            ?.takeUnless { it.endsWith(".tmp") }
            ?.let(::File)
            ?.takeIf { it.isFile }
}
