/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.Manga
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
 * The call sites in upstream code are `Manga.fetchHttpSourceMangaThumbnail`
 * ([originalResponse]), the thumbnail endpoint ([serve]) and every place
 * that clears a cached cover ([invalidate], [invalidateAll]).
 */
object FullSizeCovers {
    private val applicationDirs: ApplicationDirs by injectLazy()

    /** Shared with upstream cache invalidation; has no dependencies, so clearing never initialises the service. */
    val publication = CoverPublicationGate()

    val service: FullSizeCoverService by lazy {
        val root = File(applicationDirs.dataRoot, "komu-covers")
        FullSizeCoverService(
            preferences = CoverPreferences(File(root, "preferences.properties")),
            store = CoverVerdictStore(File(root, "verdicts")),
            inspector = { CoverImageInspector.platform },
            http = SourceCoverHttp,
            catalog = ServerCoverCatalog(applicationDirs),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            publication = publication,
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

    /**
     * The cover of [mangaId] as upstream serves it, opened first, with the
     * state of exactly those bytes ([FullSizeCoverService.serve]).
     */
    suspend fun serve(mangaId: Int): ServedCover {
        val opened = Manga.getMangaThumbnail(mangaId)
        val service = runCatching { service }.getOrNull() ?: return ServedCover(opened.first, opened.second, CoverServeState.NOT_APPLICABLE)
        return service.serve(mangaId, opened)
    }

    /** Runs an upstream clear of [mangaId]'s cached cover under the publication gate. */
    fun <T> invalidate(
        mangaId: Int,
        clear: () -> T,
    ): T = publication.invalidate(mangaId, clear)

    /** Runs an upstream clear of a whole cover cache under the publication gate. */
    fun <T> invalidateAll(clear: () -> T): T = publication.invalidateAll(clear)
}

/** Each source's own client and headers (rate limits, Cloudflare handling, referer) behind [CoverEgress]. */
internal object SourceCoverHttp : CoverHttp {
    private val egress = CoverEgress()

    override suspend fun get(
        sourceId: Long,
        url: String,
        allowedHosts: Set<String>,
        maximumBytes: Int,
    ): CoverFetch {
        val source = GetSource.getSourceOrNull(sourceId) as? HttpSource ?: throw IOException("Source $sourceId is unavailable")
        return egress.get(source.client, source.headers, url, allowedHosts, maximumBytes)
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
