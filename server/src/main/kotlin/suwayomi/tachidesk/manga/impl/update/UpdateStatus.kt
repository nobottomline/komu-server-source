package suwayomi.tachidesk.manga.impl.update

import com.fasterxml.jackson.annotation.JsonIgnore
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass

/**
 * Komu (additive): which library update run a status belongs to, the run's
 * outcome, and the order of status emissions.
 *
 * - [runId] identifies one library update request (returned by
 *   `POST update/fetch`); `null` before any run since the server started.
 * - [runState] is that run's state; COMPLETED, CANCELLED and FAILED are
 *   terminal and are always delivered in an emission.
 * - [sequence] increases by one with every status emission (WebSocket and
 *   `update/summary` carry the value of the latest emission). A snapshot
 *   (on connect, on request) repeats the latest emission's sequence, so a
 *   client correlating by run and sequence can tell snapshots from news.
 */
enum class UpdateRunState {
    QUEUED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == FAILED
}

/** Komu (additive): the response of `POST update/fetch`. */
data class LibraryUpdateStarted(
    val runId: String,
)

data class UpdateStatus(
    val categoryStatusMap: Map<CategoryUpdateStatus, List<CategoryDataClass>> = emptyMap(),
    val mangaStatusMap: Map<JobStatus, List<MangaDataClass>> = emptyMap(),
    val running: Boolean = false,
    @JsonIgnore
    val numberOfJobs: Int = 0,
    val runId: String? = null,
    val runState: UpdateRunState? = null,
    val sequence: Long = 0,
) {
    constructor(
        categories: Map<CategoryUpdateStatus, List<CategoryDataClass>>,
        jobs: List<UpdateJob>,
        skippedMangas: List<MangaDataClass>,
        running: Boolean,
    ) : this(
        categories,
        mangaStatusMap =
            jobs
                .groupBy { it.status }
                .mapValues { entry ->
                    entry.value.map { it.manga }
                }.plus(Pair(JobStatus.SKIPPED, skippedMangas)),
        running = running,
        numberOfJobs = jobs.size,
    )
}

data class UpdateUpdates(
    val isRunning: Boolean = false,
    val categoryUpdates: List<CategoryUpdateJob>,
    val mangaUpdates: List<UpdateJob>,
    val totalJobs: Int,
    val finishedJobs: Int,
    val skippedCategoriesCount: Int,
    val skippedMangasCount: Int,
    val initial: UpdateUpdates?,
)
