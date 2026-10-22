package suwayomi.tachidesk.manga.impl.download

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.websocket.WsContext
import io.javalin.websocket.WsMessageContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.ChapterDownloadReorder
import suwayomi.tachidesk.manga.impl.download.model.DownloadChapter
import suwayomi.tachidesk.manga.impl.download.model.DownloadQueueItem
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Error
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Queued
import suwayomi.tachidesk.manga.impl.download.model.DownloadStatus
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdate
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdates
import suwayomi.tachidesk.manga.impl.download.model.OldDownloadStatus
import suwayomi.tachidesk.manga.impl.download.model.Status
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.util.sampleWhenPending
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.jvm.jvmName
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = ConcurrentHashMap<String, WsContext>()
    private val downloadQueue = CopyOnWriteArrayList<DownloadQueueItem>()
    private val downloadUpdates = LinkedHashMap<Int, DownloadUpdate>()
    private val downloadUpdatesLock = Any()
    private val downloaders = ConcurrentHashMap<Long, Downloader>()

    private val downloaderSchedulingLock = Any()
    private var downloadsRequested = false

    private const val DOWNLOAD_QUEUE_KEY = "downloadQueueKey"
    private val sharedPreferences =
        Injekt.get<Application>().getSharedPreferences(DownloadManager::class.jvmName, Context.MODE_PRIVATE)

    private fun loadDownloadQueue(): List<Int> =
        sharedPreferences
            .getStringSet(DOWNLOAD_QUEUE_KEY, emptySet())
            ?.mapNotNull {
                it.toInt()
            }.orEmpty()

    private fun saveDownloadQueue() {
        sharedPreferences
            .edit()
            .putStringSet(DOWNLOAD_QUEUE_KEY, downloadQueue.map { it.chapterId.toString() }.toSet())
            .commit()
    }

    private fun triggerSaveDownloadQueue() {
        scope.launch { saveQueueFlow.emit(Unit) }
    }

    private fun handleDownloadUpdate(
        immediate: Boolean,
        download: DownloadUpdate? = null,
    ) {
        notifyAllClients(immediate, listOfNotNull(download))
    }

    fun restoreAndResumeDownloads() {
        scope.launch {
            logger.debug { "restoreAndResumeDownloads: Restore download queue..." }
            enqueue(EnqueueInput(loadDownloadQueue()))

            if (downloadQueue.size > 0) {
                logger.info { "restoreAndResumeDownloads: Restored download queue, starting downloads..." }
            }
        }
    }

    fun addClient(ctx: WsContext) {
        clients[ctx.sessionId()] = ctx
    }

    fun removeClient(ctx: WsContext) {
        clients.remove(ctx.sessionId())
    }

    fun notifyClient(ctx: WsContext) {
        ctx.send(
            getStatus(),
        )
    }

    fun handleRequest(ctx: WsMessageContext) {
        when (ctx.message()) {
            "STATUS" -> {
                notifyClient(ctx)
            }

            else -> {
                ctx.send(
                    """
                        |Invalid command.
                        |Supported commands are:
                        |    - STATUS
                        |       sends the current download status
                        |
                    """.trimMargin(),
                )
            }
        }
    }

    private val notifyFlow = Channel<Unit>(Channel.CONFLATED)

    @Deprecated("Replaced with updatesFlow", replaceWith = ReplaceWith("updatesFlow"))
    private val statusFlow = MutableSharedFlow<DownloadStatus>()

    @Deprecated("Replaced with updates", replaceWith = ReplaceWith("updates"))
    val status = statusFlow.onStart { emit(getStatus()) }

    private val updatesFlow = MutableSharedFlow<DownloadUpdates>()
    val updates = updatesFlow.onStart { emit(getDownloadUpdates(addInitial = true)) }

    init {
        scope.launch {
            notifyFlow.receiveAsFlow().sampleWhenPending(1.seconds).collect {
                notifyAllClients(immediate = true, gqlEmit = true)
            }
        }
    }

    private val saveQueueFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        saveQueueFlow.onEach { saveDownloadQueue() }.launchIn(scope)
    }

    private fun notifyAllClients(
        immediate: Boolean = false,
        downloads: List<DownloadUpdate> = emptyList(),
        gqlEmit: Boolean = false,
    ) {
        synchronized(downloadUpdatesLock) {
            downloads.forEach { downloadUpdates[it.downloadQueueItem.chapterId] = it }
        }

        // Immediate REST updates must also arm a GraphQL flush: the last terminal
        // event may be the only event left after a source fails or the queue drains.
        if (!gqlEmit) {
            notifyFlow.trySend(Unit)
        }

        // There is a problem where too many immediate updates can cause the client to lag out (e.g., in case it has to
        // update the queue in the cache based on the updates).
        // This happens in case e.g., a source is broken and all its downloads error out basically immediately.
        // With each errored out download, a new one starts, which causes an immediate notification to the clients.
        // While the immediate notification functionality is no longer needed for the latest graphql download subscription,
        // it is still required for the deprecated version as well as the rest api subscription.
        if (gqlEmit) {
            val updates =
                synchronized(downloadUpdatesLock) {
                    getDownloadUpdates().also { downloadUpdates.clear() }
                }

            scope.launch {
                updatesFlow.emit(updates)
            }
        }

        if (immediate) {
            val status = getStatus()
            scope.launch {
                statusFlow.emit(status)
                if (clients.isNotEmpty()) {
                    val status = getOldStatus(status)
                    clients.forEach {
                        it.value.send(status)
                    }
                }
            }

            return
        }
    }

    fun getStatus(): DownloadStatus =
        DownloadStatus(
            if (downloaders.values.any { it.isActive }) {
                Status.Started
            } else {
                Status.Stopped
            },
            downloadQueue.toList(),
        )

    fun getOldStatus(status: DownloadStatus): OldDownloadStatus =
        OldDownloadStatus(
            status.status,
            run {
                val items = status.queue
                val mangaIds = items.map { it.mangaId }.toSet()
                val chapterIds = items.map { it.chapterId }.toSet()
                transaction {
                    val mangas =
                        MangaTable
                            .selectAll()
                            .where { MangaTable.id inList mangaIds }
                            .associateBy({ it[MangaTable.id].value }, { MangaTable.toDataClass(it) })
                    val chapters =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id inList chapterIds }
                            .associateBy({ it[ChapterTable.id].value }, { ChapterTable.toDataClass(it) })
                    items.mapNotNull {
                        DownloadChapter(
                            it.chapterIndex,
                            it.mangaId,
                            chapters[it.chapterId] ?: return@mapNotNull null,
                            mangas[it.mangaId] ?: return@mapNotNull null,
                            it.state,
                            it.progress,
                            it.tries,
                        )
                    }
                }
            },
        )

    private fun getDownloadUpdates(addInitial: Boolean = false): DownloadUpdates =
        DownloadUpdates(
            if (downloaders.values.any { it.isActive }) {
                Status.Started
            } else {
                Status.Stopped
            },
            synchronized(downloadUpdatesLock) { downloadUpdates.values.toList() },
            if (addInitial) downloadQueue.toList() else null,
        )

    private val downloaderWatch = Channel<Unit>(Channel.CONFLATED)

    init {
        serverConfig.subscribeTo(serverConfig.maxSourcesInParallel, { maxSourcesInParallel ->
            val runningDownloaders = downloaders.values.filter { it.isActive }
            var downloadersToStop = runningDownloaders.size - maxSourcesInParallel

            logger.debug { "Max sources in parallel changed to $maxSourcesInParallel (running downloaders ${runningDownloaders.size})" }

            if (downloadersToStop > 0) {
                runningDownloaders.takeWhile {
                    it.stop()
                    --downloadersToStop > 0
                }
            } else {
                refreshDownloaders()
            }
        })

        scope.launch {
            downloaderWatch.receiveAsFlow().sampleWhenPending(1.seconds).collect {
                synchronized(downloaderSchedulingLock) {
                    if (!downloadsRequested) return@synchronized
                    val runningDownloaders = downloaders.values.filter { it.isActive }
                    val availableDownloads = downloadQueue.filter { it.state != Error }

                    logger.info {
                        "Running: ${runningDownloaders.size}, " +
                            "Queued: ${availableDownloads.size}, " +
                            "Failed: ${downloadQueue.size - availableDownloads.size}"
                    }

                    if (runningDownloaders.size < serverConfig.maxSourcesInParallel.value) {
                        availableDownloads
                            .asSequence()
                            .map { it.sourceId }
                            .distinct()
                            .minus(
                                runningDownloaders.map { it.sourceId }.toSet(),
                            ).take((serverConfig.maxSourcesInParallel.value - runningDownloaders.size).coerceAtLeast(0))
                            .map { getDownloader(it) }
                            .forEach {
                                it.start()
                            }
                        notifyAllClients()
                    }
                }
            }
        }
    }

    private fun refreshDownloaders() {
        downloaderWatch.trySend(Unit)
    }

    private fun getDownloader(sourceId: Long) =
        downloaders.getOrPut(sourceId) {
            Downloader(
                scope = scope,
                sourceId = sourceId,
                downloadQueue = downloadQueue,
                notifier = ::handleDownloadUpdate,
                onComplete = ::refreshDownloaders,
                onDownloadFinished = ::triggerSaveDownloadQueue,
            )
        }

    fun enqueueWithChapterIndex(
        mangaId: Int,
        chapterIndex: Int,
    ) {
        val chapter =
            transaction {
                ChapterTable
                    .select(ChapterTable.id)
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }
                    .first()
            }
        enqueue(EnqueueInput(chapterIds = listOf(chapter[ChapterTable.id].value)))
    }

    @Serializable
    // Input might have additional formats in the future, such as "All for mangaID" or "Unread for categoryID"
    // Having this input format is just future-proofing
    data class EnqueueInput(
        val chapterIds: List<Int>?,
    )

    fun enqueue(input: EnqueueInput) {
        if (input.chapterIds.isNullOrEmpty()) return

        val chapters =
            transaction {
                (ChapterTable innerJoin MangaTable)
                    .selectAll()
                    .where { ChapterTable.id inList input.chapterIds }
                    .orderBy(ChapterTable.manga)
                    .orderBy(ChapterTable.sourceOrder)
                    .toList()
            }

        val mangas =
            transaction {
                chapters
                    .distinctBy { chapter -> chapter[MangaTable.id] }
                    .map { MangaTable.toDataClass(it) }
                    .associateBy { it.id }
            }

        val inputPairs =
            transaction {
                chapters.map {
                    Pair(
                        // this should be safe because mangas is created above from chapters
                        mangas[it[ChapterTable.manga].value]!!,
                        ChapterTable.toDataClass(it),
                    )
                }
            }

        addMultipleToQueue(inputPairs)
    }

    /**
     * Tries to add multiple inputs to queue
     * If any of inputs was actually added to queue, starts the queue
     */
    private fun addMultipleToQueue(inputs: List<Pair<MangaDataClass, ChapterDataClass>>) {
        val size = downloadQueue.size
        val addedChapters = inputs.mapNotNull { addToQueue(it.first, it.second) }
        if (addedChapters.isNotEmpty()) {
            start()
            notifyAllClients(
                false,
                addedChapters.mapIndexed { index, item ->
                    DownloadUpdate(
                        DownloadUpdateType.QUEUED,
                        item,
                        size + index,
                    )
                },
            )
            triggerSaveDownloadQueue()
        }
    }

    /**
     * Tries to add chapter to queue.
     * If chapter is added, returns the created DownloadChapter, otherwise returns null
     */
    private fun addToQueue(
        manga: MangaDataClass,
        chapter: ChapterDataClass,
    ): DownloadQueueItem? {
        val downloadChapter = downloadQueue.firstOrNull { it.chapterId == chapter.id }

        val addToQueue = downloadChapter == null
        if (addToQueue) {
            val newDownloadChapter =
                DownloadQueueItem(
                    chapter.id,
                    chapter.index,
                    manga.id,
                    manga.sourceId.toLong(),
                    downloadQueue.size,
                )
            downloadQueue.add(newDownloadChapter)
            logger.debug { "Added chapter ${chapter.id} to download queue ($newDownloadChapter)" }
            return newDownloadChapter
        }

        val retryDownload = downloadChapter.state == Error
        if (retryDownload) {
            logger.debug { "Chapter ${chapter.id} download failed, retry download ($downloadChapter)" }

            downloadChapter.state = Queued
            downloadChapter.progress = 0f

            return downloadChapter
        }

        logger.debug { "Chapter ${chapter.id} already present in queue ($downloadChapter)" }
        return null
    }

    fun dequeue(input: EnqueueInput) {
        if (input.chapterIds.isNullOrEmpty()) return
        dequeue(downloadQueue.filter { it.chapterId in input.chapterIds }.toSet())
    }

    fun dequeue(
        chapterIndex: Int,
        mangaId: Int,
    ) {
        dequeue(downloadQueue.filter { it.mangaId == mangaId && it.chapterIndex == chapterIndex }.toSet())
    }

    fun dequeue(
        mangaIds: List<Int>,
        chaptersToIgnore: List<Int> = emptyList(),
    ) {
        dequeue(downloadQueue.filter { it.mangaId in mangaIds && it.chapterId !in chaptersToIgnore }.toSet())
    }

    private fun dequeue(chapterDownloads: Set<DownloadQueueItem>) {
        logger.debug { "dequeue ${chapterDownloads.size} chapters [${chapterDownloads.joinToString(separator = ", ") { "$it" }}]" }

        downloadQueue.removeAll(chapterDownloads)
        triggerSaveDownloadQueue()

        notifyAllClients(
            false,
            chapterDownloads.toList().map { DownloadUpdate(DownloadUpdateType.DEQUEUED, it, -1) },
        )
    }

    fun reorder(
        chapterIndex: Int,
        mangaId: Int,
        to: Int,
    ) {
        val download =
            downloadQueue.find { it.mangaId == mangaId && it.chapterIndex == chapterIndex }
                ?: return

        reorder(listOf(ChapterDownloadReorder(download.chapterId, to)))
    }

    fun reorder(
        chapterId: Int,
        to: Int,
    ) {
        reorder(listOf(ChapterDownloadReorder(chapterId, to)))
    }

    fun reorder(reorders: List<ChapterDownloadReorder>) {
        val updates =
            reorders.mapNotNull {
                val download = reorder(it) ?: return@mapNotNull null

                DownloadUpdate(DownloadUpdateType.POSITION, download, it.to)
            }

        notifyAllClients(false, updates)
        triggerSaveDownloadQueue()
    }

    private fun reorder(reorder: ChapterDownloadReorder): DownloadQueueItem? {
        val (chapterId, to) = reorder

        require(to >= 0) { "'to' must be over or equal to 0" }

        val download = downloadQueue.find { it.chapterId == chapterId } ?: return null

        logger.debug { "reorder download $download from ${downloadQueue.indexOf(download)} to $to" }

        downloadQueue -= download
        downloadQueue.add(to, download)

        return download
    }

    fun start() {
        logger.debug { "start" }

        synchronized(downloaderSchedulingLock) {
            downloadsRequested = true
            refreshDownloaders()
        }
    }

    suspend fun stop() {
        logger.debug { "stop" }
        val running =
            synchronized(downloaderSchedulingLock) {
                downloadsRequested = false
                downloaders.values.toList()
            }

        coroutineScope {
            running
                .map { downloader ->
                    async {
                        downloader.stop()
                    }
                }.awaitAll()
        }
        notifyAllClients()
    }

    suspend fun clear() {
        logger.debug { "clear" }

        stop()
        val removedDownloads = downloadQueue.toList().map { DownloadUpdate(DownloadUpdateType.DEQUEUED, it, -1) }
        downloadQueue.clear()
        triggerSaveDownloadQueue()
        notifyAllClients(false, removedDownloads)
    }
}
