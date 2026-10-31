package suwayomi.tachidesk.manga.impl.update

import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass

enum class JobStatus {
    PENDING,
    RUNNING,
    COMPLETE,
    FAILED,
    SKIPPED,
}

data class UpdateJob(
    val manga: MangaDataClass,
    val status: JobStatus = JobStatus.PENDING,
    /**
     * Komu: the library update run that created this job; `null` for jobs
     * queued outside a run (`addMangasToQueue`), which keep the upstream
     * behaviour. A job of a run that is no longer current never changes
     * the tracker or publishes.
     */
    val runId: String? = null,
)

enum class CategoryUpdateStatus {
    UPDATING,
    SKIPPED,
}

data class CategoryUpdateJob(
    val category: CategoryDataClass,
    val status: CategoryUpdateStatus,
)
