package suwayomi.tachidesk.manga.impl.update

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass

interface IUpdater {
    fun getLastUpdateTimestamp(): Long

    fun deleteLastAutomatedUpdateTimestamp()

    fun addCategoriesToUpdateQueue(
        categories: List<CategoryDataClass>,
        clear: Boolean?,
        forceAll: Boolean,
    )

    /**
     * Komu: starts a library update like [addCategoriesToUpdateQueue] and
     * returns its run identity, which every status of that run carries
     * ([UpdateStatus.runId]).
     */
    fun startLibraryUpdate(
        categories: List<CategoryDataClass>,
        clear: Boolean?,
        forceAll: Boolean,
    ): String {
        addCategoriesToUpdateQueue(categories, clear, forceAll)
        return ""
    }

    fun addMangasToQueue(mangas: List<MangaDataClass>)

    @Deprecated("Replaced with updates", replaceWith = ReplaceWith("updates"))
    val status: Flow<UpdateStatus>

    val updates: Flow<UpdateUpdates>

    val statusDeprecated: StateFlow<UpdateStatus>

    fun reset()

    fun getStatus(): UpdateUpdates
}
