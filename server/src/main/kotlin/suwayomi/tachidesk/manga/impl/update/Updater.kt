package suwayomi.tachidesk.manga.impl.update

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import suwayomi.tachidesk.global.impl.sync.SyncManager
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.util.HAScheduler
import suwayomi.tachidesk.util.sampleWhenPending
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class Updater : IUpdater {
    private val logger = KotlinLogging.logger {}
    private val notifyFlowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val notifyFlow = Channel<Unit>(Channel.CONFLATED)

    // Komu: library update runs (see UpdateStatus). A start coroutine lives
    // outside `scope`, which clearing the queue cancels.
    private val startScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val emissionSequence = AtomicLong(0)
    private val serverInstanceId = UUID.randomUUID().toString()

    // Run state, the tracker's membership and the queue change together
    // under this lock: a stop, a replacement and an enqueue never interleave.
    private val runLock = Any()
    private var runId: String? = null
    private var runState: UpdateRunState? = null
    private var startJob: Job? = null

    // The single writer of deprecated statuses: building a status,
    // assigning its sequence and publishing it to the flow and the snapshot
    // happen in one critical section, so sequences reach every subscriber
    // in order and the snapshot is always the last published status.
    private val publishMutex = Mutex()

    /** Test seam: suspends a run right before it queues its titles. */
    internal var beforeEnqueue: (suspend (runId: String) -> Unit)? = null

    /** Test seam: suspends a job right before it records its result. */
    internal var beforeJobResult: (suspend (UpdateJob) -> Unit)? = null

    // Komu: buffered, latest wins for a slow subscriber. Statuses are whole
    // snapshots ordered by `sequence`, so a subscriber that falls behind
    // skips stale ones instead of holding up every publication.
    @Deprecated("Replaced with updatesFlow", replaceWith = ReplaceWith("updatesFlow"))
    private val statusFlow =
        MutableSharedFlow<UpdateStatus>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Deprecated("Replaced with updates", replaceWith = ReplaceWith("updates"))
    override val status = statusFlow.onStart { emit(_status.value) }

    private val updatesFlow = MutableSharedFlow<UpdateUpdates>()
    override val updates = updatesFlow.onStart { emit(getUpdates(addInitial = true)) }

    // Komu: the deltas waiting for a slow `updates` subscriber, merged in
    // publication order (guarded by `publishMutex`). One forwarder delivers
    // them, so a subscriber's backpressure stays with the forwarder and no
    // title's latest state is lost or reordered.
    private var pendingUpdates: UpdateUpdates? = null
    private val updatesPending = Channel<Unit>(Channel.CONFLATED)

    init {
        notifyFlowScope.launch {
            for (signal in updatesPending) {
                val next = publishMutex.withLock { pendingUpdates.also { pendingUpdates = null } } ?: continue
                updatesFlow.emit(next)
            }
        }
    }

    init {
        // has to be in its own scope (notifyFlowScope), otherwise, the collection gets canceled due to canceling the scopes (scope) children in the reset function
        notifyFlowScope.launch {
            notifyFlow.receiveAsFlow().sampleWhenPending(1.seconds).collect {
                updateStatus(immediate = true)
            }
        }
    }

    private val _status = MutableStateFlow(UpdateStatus(serverInstanceId = serverInstanceId))
    override val statusDeprecated = _status.asStateFlow()

    private val mangaUpdates = ConcurrentHashMap<Int, UpdateJob>()
    private val categoryUpdates = ConcurrentHashMap<Int, CategoryUpdateJob>()
    private var updateStatusCategories: Map<CategoryUpdateStatus, List<CategoryDataClass>> = emptyMap()
    private var updateStatusSkippedMangas: List<MangaDataClass> = emptyList()

    private val tracker = ConcurrentHashMap<Int, UpdateJob>()
    private val updateChannels = ConcurrentHashMap<String, Channel<UpdateJob>>()

    private var maxSourcesInParallel = 20 // max permits, necessary to be set to be able to release up to 20 permits
    private val semaphore = Semaphore(maxSourcesInParallel)

    private val lastUpdateKey = "lastGlobalUpdate"
    private val lastAutomatedUpdateKey = "lastAutomatedGlobalUpdate"
    private val preferences = Injekt.get<Application>().getSharedPreferences("server_util", Context.MODE_PRIVATE)

    private var currentUpdateTaskId = ""

    init {
        serverConfig.subscribeTo(serverConfig.globalUpdateInterval, ::scheduleUpdateTask)
        serverConfig.subscribeTo(
            serverConfig.maxSourcesInParallel,
            { newMaxPermits ->
                val permitDifference = maxSourcesInParallel - newMaxPermits
                maxSourcesInParallel = newMaxPermits

                val addMorePermits = permitDifference < 0
                for (i in 1..permitDifference.absoluteValue) {
                    if (addMorePermits) {
                        semaphore.release()
                    } else {
                        semaphore.acquire()
                    }
                }
            },
            ignoreInitialValue = false,
        )
    }

    override fun getLastUpdateTimestamp(): Long = preferences.getLong(lastUpdateKey, 0)

    fun saveLastUpdateTimestamp() {
        preferences.edit().putLong(lastUpdateKey, System.currentTimeMillis()).apply()
    }

    fun getLastAutomatedUpdateTimestamp(): Long = preferences.getLong(lastAutomatedUpdateKey, 0)

    fun saveLastAutomatedUpdateTimestamp() {
        preferences.edit().putLong(lastAutomatedUpdateKey, System.currentTimeMillis()).apply()
    }

    override fun deleteLastAutomatedUpdateTimestamp() {
        preferences.edit().remove(lastAutomatedUpdateKey).apply()
    }

    private fun autoUpdateTask() {
        try {
            val lastAutomatedUpdate = getLastAutomatedUpdateTimestamp()
            saveLastAutomatedUpdateTimestamp()

            if (getStatus().isRunning) {
                logger.debug { "Global update is already in progress" }
                return
            }

            logger.info {
                "Trigger global update (interval= ${serverConfig.globalUpdateInterval.value}h, lastAutomatedUpdate= ${Date(
                    lastAutomatedUpdate,
                )})"
            }
            addCategoriesToUpdateQueue(Category.getCategoryList(), clear = true, forceAll = false)
        } catch (e: Exception) {
            logger.error(e) { "autoUpdateTask: failed due to" }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleUpdateTask() {
        HAScheduler.deschedule(currentUpdateTaskId)

        val isAutoUpdateDisabled = serverConfig.globalUpdateInterval.value == 0.0
        if (isAutoUpdateDisabled) {
            return
        }

        val updateInterval = serverConfig.globalUpdateInterval.value.hours.inWholeMilliseconds
        val lastAutomatedUpdate = getLastAutomatedUpdateTimestamp()
        val isInitialScheduling = lastAutomatedUpdate == 0L

        val timeToNextExecution =
            if (!isInitialScheduling) {
                (updateInterval - (System.currentTimeMillis() - lastAutomatedUpdate)).mod(updateInterval)
            } else {
                updateInterval
            }

        if (isInitialScheduling) {
            saveLastAutomatedUpdateTimestamp()
        }

        val wasPreviousUpdateTriggered =
            System.currentTimeMillis() - (
                if (!isInitialScheduling) lastAutomatedUpdate else System.currentTimeMillis()
            ) < updateInterval
        if (!wasPreviousUpdateTriggered) {
            GlobalScope.launch {
                autoUpdateTask()
            }
        }

        currentUpdateTaskId = HAScheduler.schedule(::autoUpdateTask, updateInterval, timeToNextExecution, "global-update")
    }

    private fun isRunning(): Boolean =
        tracker.values.toList().any { job -> job.status == JobStatus.PENDING || job.status == JobStatus.RUNNING }

    /** Moves run [id] to [to] when it is the current run in one of [from]. */
    private fun transitionRun(
        id: String?,
        to: UpdateRunState,
        vararg from: UpdateRunState,
    ): Boolean =
        synchronized(runLock) {
            if (id == null || runId != id || runState !in from) return@synchronized false
            runState = to
            true
        }

    private fun currentRunId(): String? = synchronized(runLock) { runId }

    private fun isQueued(id: String): Boolean = synchronized(runLock) { runId == id && runState == UpdateRunState.QUEUED }

    private fun getStatus(
        categories: List<CategoryUpdateJob>,
        mangas: List<UpdateJob>,
        running: Boolean? = null,
        addInitial: Boolean? = false,
    ): UpdateUpdates =
        UpdateUpdates(
            running ?: isRunning(),
            categories,
            mangas,
            tracker.size,
            tracker.values.count { it.status == JobStatus.COMPLETE || it.status == JobStatus.FAILED },
            this.updateStatusCategories[CategoryUpdateStatus.SKIPPED]?.size ?: 0,
            this.updateStatusSkippedMangas.size,
            if (addInitial == true) getStatus() else null,
        )

    override fun getStatus(): UpdateUpdates =
        getStatus(
            this.updateStatusCategories[CategoryUpdateStatus.UPDATING]
                ?.map {
                    CategoryUpdateJob(
                        it,
                        CategoryUpdateStatus.UPDATING,
                    )
                }.orEmpty(),
            tracker.values.toList(),
        )

    private fun getUpdates(
        running: Boolean? = null,
        addInitial: Boolean? = null,
    ): UpdateUpdates =
        getStatus(
            categoryUpdates.values.toList(),
            mangaUpdates.values.toList(),
            running,
            addInitial = addInitial,
        )

    /**
     * Pass "isRunning" to force a specific running state
     */
    private suspend fun updateStatus(
        immediate: Boolean = false,
        categoryUpdates: List<CategoryUpdateJob> = emptyList(),
        mangaUpdates: List<UpdateJob> = emptyList(),
        isRunning: Boolean? = null,
    ) {
        mangaUpdates.forEach { this.mangaUpdates[it.manga.id] = it }
        categoryUpdates.forEach { this.categoryUpdates[it.category.id] = it }

        if (immediate) {
            publish(isRunning)
            return
        }

        notifyFlow.send(Unit)
    }

    /** Everything one status says, captured together under the run lock. */
    private class StatusSnapshot(
        val runId: String?,
        val runState: UpdateRunState?,
        val jobs: List<UpdateJob>,
        val categories: Map<CategoryUpdateStatus, List<CategoryDataClass>>,
        val skipped: List<MangaDataClass>,
    )

    private fun captureLocked() =
        StatusSnapshot(runId, runState, tracker.values.toList(), updateStatusCategories, updateStatusSkippedMangas)

    /** Test seam: suspends the publication of a captured (stop) status. */
    internal var beforeCapturedPublish: (suspend () -> Unit)? = null

    /**
     * Publishes the next emission: the status as it is now, or [captured]
     * (a stop's status) only while its run is still the current one, so a
     * late publication never puts one run's titles under another run.
     *
     * The sequence and the snapshot are assigned under one mutex; the
     * status flow is buffered (`tryEmit`) and the deltas go through the
     * forwarder, so the order holds and no subscriber holds up a
     * publication.
     */
    private suspend fun publish(
        isRunning: Boolean?,
        captured: StatusSnapshot? = null,
    ) {
        if (captured != null) beforeCapturedPublish?.invoke()
        publishMutex.withLock {
            val snapshot =
                if (captured != null) {
                    if (synchronized(runLock) { runId } != captured.runId) return
                    captured
                } else {
                    synchronized(runLock) { captureLocked() }
                }
            val running =
                isRunning ?: snapshot.jobs.any { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING }
            val status =
                UpdateStatus(snapshot.categories, snapshot.jobs, snapshot.skipped, running).copy(
                    runId = snapshot.runId,
                    runState = snapshot.runState,
                    sequence = emissionSequence.incrementAndGet(),
                    serverInstanceId = serverInstanceId,
                )
            val updates = getUpdates(isRunning)

            this.mangaUpdates.clear()
            this.categoryUpdates.clear()

            _status.update { status }
            statusFlow.tryEmit(status)
            pendingUpdates = pendingUpdates?.then(updates) ?: updates
        }
        updatesPending.trySend(Unit)
    }

    /** This delta followed by [next]: [next]'s totals, the latest job per title and category. */
    private fun UpdateUpdates.then(next: UpdateUpdates): UpdateUpdates =
        next.copy(
            categoryUpdates = (categoryUpdates + next.categoryUpdates).associateBy { it.category.id }.values.toList(),
            mangaUpdates = (mangaUpdates + next.mangaUpdates).associateBy { it.manga.id }.values.toList(),
            initial = next.initial ?: initial,
        )

    /** The job may change the tracker: it belongs to the running run (or to no run). */
    private fun ownsLocked(job: UpdateJob): Boolean = job.runId == null || (job.runId == runId && runState == UpdateRunState.RUNNING)

    private fun getOrCreateUpdateChannelFor(source: String): Channel<UpdateJob> =
        updateChannels.getOrPut(source) {
            logger.debug { "getOrCreateUpdateChannelFor: created channel for $source - channels: ${updateChannels.size + 1}" }
            createUpdateChannel(source)
        }

    private fun createUpdateChannel(source: String): Channel<UpdateJob> {
        val channel = Channel<UpdateJob>(Channel.UNLIMITED)
        channel
            .consumeAsFlow()
            .onEach { job ->
                semaphore.withPermit {
                    process(job)
                }
            }.catch {
                logger.error(it) { "Error during updates (source: $source)" }
                handleChannelUpdateFailure(source)
            }.onCompletion { updateChannels.remove(source) }
            .launchIn(scope)
        return channel
    }

    private suspend fun handleChannelUpdateFailure(source: String) {
        val isFailedSourceUpdate = { job: UpdateJob ->
            val isForSource = job.manga.sourceId == source
            val hasFailed = job.status == JobStatus.FAILED

            isForSource && hasFailed
        }

        // fail all updates for source
        val sourceUpdateJobs = tracker.filter { (_, job) -> !isFailedSourceUpdate(job) }
        sourceUpdateJobs.forEach { (mangaId, job) -> tracker[mangaId] = job.copy(status = JobStatus.FAILED) }

        updateStatus(mangaUpdates = sourceUpdateJobs.values.toList())
    }

    private suspend fun process(job: UpdateJob) {
        // A job of a stopped or replaced run neither runs nor publishes.
        val started =
            synchronized(runLock) {
                if (!ownsLocked(job)) return
                job.copy(status = JobStatus.RUNNING).also { tracker[job.manga.id] = it }
            }
        updateStatus(mangaUpdates = listOf(started))

        val result =
            try {
                logger.info { "Updating ${job.manga}" }
                Manga.updateMangaAndChapters(
                    job.manga.id,
                    updateManga = serverConfig.updateMangas.value || !job.manga.initialized,
                )
                job.copy(status = JobStatus.COMPLETE)
            } catch (e: Exception) {
                logger.error(e) { "Error while updating ${job.manga}" }
                if (e is CancellationException) throw e
                job.copy(status = JobStatus.FAILED)
            }
        beforeJobResult?.invoke(job)

        val wasLastJob =
            synchronized(runLock) {
                if (!ownsLocked(job)) return
                tracker[job.manga.id] = result
                tracker.values.none { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING }
            }

        // in case this is the last update job, the running flag has to be true, before it gets set to false, to be able
        // to properly clear the dataloader store in UpdateType
        updateStatus(immediate = wasLastJob, isRunning = true, mangaUpdates = listOf(result))

        if (wasLastJob) {
            // Fails when a stop or a newer run came first: nothing is revived.
            if (job.runId == null || transitionRun(job.runId, UpdateRunState.COMPLETED, UpdateRunState.RUNNING)) {
                updateStatus(isRunning = false)
            }
        }
    }

    override fun addCategoriesToUpdateQueue(
        categories: List<CategoryDataClass>,
        clear: Boolean?,
        forceAll: Boolean,
    ) {
        startLibraryUpdate(categories, clear, forceAll)
    }

    override fun startLibraryUpdate(
        categories: List<CategoryDataClass>,
        clear: Boolean?,
        forceAll: Boolean,
    ): String {
        val id = UUID.randomUUID().toString()
        synchronized(runLock) {
            // A newer run replaces the previous one's start.
            startJob?.cancel()
            runId = id
            runState = UpdateRunState.QUEUED
            startJob =
                startScope.launch {
                    try {
                        queueLibraryUpdate(id, categories, clear, forceAll)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Library update $id could not start" }
                        if (transitionRun(id, UpdateRunState.FAILED, UpdateRunState.QUEUED, UpdateRunState.RUNNING)) {
                            updateStatus(immediate = true, isRunning = false)
                        }
                    }
                }
        }
        return id
    }

    private suspend fun queueLibraryUpdate(
        id: String,
        categories: List<CategoryDataClass>,
        clear: Boolean?,
        forceAll: Boolean,
    ) {
        run {
            SyncManager.ensureSync()

            saveLastUpdateTimestamp()

            // Stopped (or replaced) while syncing: nothing is queued.
            if (!isQueued(id)) return

            if (clear == true) {
                synchronized(runLock) {
                    if (!(runId == id && runState == UpdateRunState.QUEUED)) return
                    clearQueue()
                }
                updateStatus(immediate = true, isRunning = false)
            }

            val includeInUpdateStatusToCategoryMap = categories.groupBy { it.includeInUpdate }
            val excludedCategories = includeInUpdateStatusToCategoryMap[IncludeOrExclude.EXCLUDE].orEmpty()
            val includedCategories = includeInUpdateStatusToCategoryMap[IncludeOrExclude.INCLUDE].orEmpty()
            val unsetCategories = includeInUpdateStatusToCategoryMap[IncludeOrExclude.UNSET].orEmpty()
            val categoriesToUpdate =
                if (forceAll) {
                    categories
                } else {
                    includedCategories.ifEmpty { unsetCategories }
                }
            val skippedCategories = categories.subtract(categoriesToUpdate.toSet()).toList()
            val updateStatusCategories =
                mapOf(
                    Pair(CategoryUpdateStatus.UPDATING, categoriesToUpdate),
                    Pair(CategoryUpdateStatus.SKIPPED, skippedCategories),
                )

            logger.debug { "Updating categories: '${categoriesToUpdate.joinToString("', '") { it.name }}'" }

            val categoriesToUpdateMangas =
                categoriesToUpdate
                    .flatMap { CategoryManga.getCategoryMangaList(it.id) }
                    .distinctBy { it.id }
            val mangasToCategoriesMap = CategoryManga.getMangasCategories(categoriesToUpdateMangas.map { it.id })
            val mangasToUpdate =
                categoriesToUpdateMangas
                    .asSequence()
                    .filter { it.updateStrategy == UpdateStrategy.ALWAYS_UPDATE }
                    .filter {
                        if (serverConfig.excludeUnreadChapters.value) {
                            (it.unreadCount ?: 0L) == 0L
                        } else {
                            true
                        }
                    }.filter {
                        if (it.initialized && serverConfig.excludeNotStarted.value) {
                            it.lastReadAt != null
                        } else {
                            true
                        }
                    }.filter {
                        if (serverConfig.excludeCompleted.value) {
                            it.status != MangaStatus.COMPLETED.name
                        } else {
                            true
                        }
                    }.filter {
                        forceAll ||
                            !excludedCategories.any { category ->
                                mangasToCategoriesMap[it.id]?.contains(category) == true
                            }
                    }.toList()
            val skippedMangas = categoriesToUpdateMangas.subtract(mangasToUpdate.toSet()).toList()

            beforeEnqueue?.invoke(id)

            // The decision and the queueing happen under the run lock: a stop
            // or a newer run either comes before (nothing is queued) or after
            // (it clears what was queued).
            val queued =
                synchronized(runLock) {
                    if (!(runId == id && runState == UpdateRunState.QUEUED)) return
                    this@Updater.updateStatusCategories = updateStatusCategories
                    this@Updater.updateStatusSkippedMangas = skippedMangas
                    if (mangasToUpdate.isEmpty()) {
                        runState = UpdateRunState.COMPLETED
                        return@synchronized false
                    }
                    runState = UpdateRunState.RUNNING
                    val ordered = mangasToUpdate.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, MangaDataClass::title))
                    // create all jobs before queueing them so that the client can calculate the progress right from the start
                    ordered.forEach { tracker[it.id] = UpdateJob(it, runId = id) }
                    ordered.forEach { addMangaToQueue(it, id) }
                    true
                }

            if (!queued) {
                // In case no manga gets updated and no update job was running before, the client would never receive an info
                // about its update request
                updateStatus(immediate = true)
                return
            }

            scope.launch {
                updateStatus(
                    categoryUpdates =
                        updateStatusCategories[CategoryUpdateStatus.UPDATING]
                            ?.map {
                                CategoryUpdateJob(it, CategoryUpdateStatus.UPDATING)
                            }.orEmpty(),
                    mangaUpdates = mangasToUpdate.map { UpdateJob(it, runId = id) },
                    isRunning = true,
                )
            }
        }
    }

    override fun addMangasToQueue(mangas: List<MangaDataClass>) {
        // create all manga update jobs before adding them to the queue so that the client is able to calculate the
        // progress properly right form the start
        mangas.forEach { tracker[it.id] = UpdateJob(it) }
        mangas.forEach { addMangaToQueue(it, runId = null) }
    }

    private fun addMangaToQueue(
        manga: MangaDataClass,
        runId: String?,
    ) {
        val updateChannel = getOrCreateUpdateChannelFor(manga.sourceId)
        scope.launch {
            updateChannel.send(UpdateJob(manga, runId = runId))
        }
    }

    override fun reset() {
        // Komu: a stop ends the current run as CANCELLED. Its last status
        // (its id, CANCELLED, the titles checked so far and the rest still
        // pending) is captured with the transition, under the run lock,
        // before the queue is cleared.
        val captured =
            synchronized(runLock) {
                // The start coroutine stops too: nothing is queued after this.
                startJob?.cancel()
                startJob = null
                val active = runId != null && (runState == UpdateRunState.QUEUED || runState == UpdateRunState.RUNNING)
                if (active) runState = UpdateRunState.CANCELLED
                val snapshot = if (active) captureLocked() else null
                clearQueue()
                snapshot
            }
        // Outside `scope`: a start right after the stop clears `scope`, and
        // the cancelled status must still be published (while it is current).
        notifyFlowScope.launch {
            publish(isRunning = false, captured = captured)
        }
    }

    /** Cancels the queued and running jobs and clears the run's state. */
    private fun clearQueue() {
        scope.coroutineContext.cancelChildren()

        tracker.clear()
        this.mangaUpdates.clear()
        this.categoryUpdates.clear()
        this.updateStatusCategories = emptyMap()
        this.updateStatusSkippedMangas = emptyList()

        updateChannels.forEach { (_, channel) -> channel.cancel() }
        updateChannels.clear()
    }
}
