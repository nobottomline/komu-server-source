package suwayomi.tachidesk.manga.impl.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Komu: every library update run has an identity returned by its request, a
 * strictly increasing emission sequence and an explicit terminal state, so a
 * client correlates statuses without timing rules.
 */
// Runs use a dedicated category with `forceAll`: other suites may leave the
// default category excluded from updates, and these tests are about runs,
// not category rules.
class LibraryUpdateRunTest : ApplicationTest() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var categoryId: Int? = null

    @AfterEach
    fun cleanup() {
        scope.cancel()
        categoryId?.let { Category.removeCategory(it) }
        clearTables(CategoryMangaTable, ChapterTable, MangaTable)
    }

    /** A category holding [titles] new library titles. */
    private fun category(vararg titles: String): List<CategoryDataClass> {
        val id = Category.createCategory("Library update run fixture")
        categoryId = id
        titles.forEach { CategoryManga.addMangaToCategory(createLibraryManga(it), id) }
        return listOf(assertNotNull(Category.getCategoryById(id)))
    }

    private fun Updater.record(): MutableList<UpdateStatus> {
        val frames = CopyOnWriteArrayList<UpdateStatus>()
        status.onEach { frames.add(it) }.launchIn(scope)
        // The collector's opening snapshot arrives first.
        waitFor { frames.isNotEmpty() }
        return frames
    }

    private fun waitFor(
        timeoutMillis: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `an empty library run ends COMPLETED under its own run id`() {
        val updater = Updater()
        val frames = updater.record()
        val opening = frames.first()

        val runId = updater.startLibraryUpdate(category(), clear = true, forceAll = true)

        assertTrue(runId.isNotEmpty())
        waitFor { frames.any { it.runId == runId && it.runState?.isTerminal == true } }
        val terminal = frames.first { it.runId == runId && it.runState?.isTerminal == true }
        assertEquals(UpdateRunState.COMPLETED, terminal.runState)
        assertTrue(terminal.sequence > opening.sequence, "a terminal state is an emission, never a snapshot")
        assertEquals(runId, updater.statusDeprecated.value.runId, "update/summary reports the same run")
    }

    @Test
    fun `a run's statuses carry its id in a strictly increasing sequence until COMPLETED`() {
        val categories = category("Run fixture A", "Run fixture B")
        val updater = Updater()
        val frames = updater.record()

        val runId = updater.startLibraryUpdate(categories, clear = true, forceAll = true)

        waitFor { frames.any { it.runId == runId && it.runState?.isTerminal == true } }
        val emitted = frames.drop(1)
        assertTrue(emitted.all { it.runId == runId }, "every emission after the request belongs to the run")
        val sequences = emitted.map { it.sequence }
        assertEquals(sequences.sorted().distinct(), sequences, "emissions are strictly ordered")
        val terminal = emitted.last()
        assertEquals(UpdateRunState.COMPLETED, terminal.runState)
        assertTrue(!terminal.running)
        val finished = terminal.mangaStatusMap.filterKeys { it == JobStatus.COMPLETE || it == JobStatus.FAILED }.values.sumOf { it.size }
        assertEquals(2, finished, "both titles were checked (their source is unavailable here)")
    }

    @Test
    fun `stop ends the run CANCELLED and keeps the titles it had`() {
        val categories = category("Stop fixture")
        val updater = Updater()
        val frames = updater.record()

        val runId = updater.startLibraryUpdate(categories, clear = true, forceAll = true)
        updater.reset()

        waitFor { frames.any { it.runId == runId && it.runState?.isTerminal == true } }
        val terminal = frames.first { it.runId == runId && it.runState?.isTerminal == true }
        assertEquals(UpdateRunState.CANCELLED, terminal.runState)
        // A queued start that the stop overtook must not revive the run or
        // check its titles.
        Thread.sleep(1_500)
        assertEquals(UpdateRunState.CANCELLED, updater.statusDeprecated.value.runState)
        assertEquals(runId, updater.statusDeprecated.value.runId)
        val afterStop = frames.dropWhile { it.runState != UpdateRunState.CANCELLED }
        assertTrue(afterStop.none { it.runState == UpdateRunState.RUNNING || it.running }, "nothing runs after the stop")
    }

    @Test
    fun `a snapshot repeats the latest emission's sequence`() {
        val updater = Updater()
        val runId = updater.startLibraryUpdate(category(), clear = true, forceAll = true)
        waitFor { updater.statusDeprecated.value.runState?.isTerminal == true }
        val latest = updater.statusDeprecated.value
        val frames = updater.record()
        val snapshot = assertNotNull(frames.firstOrNull())
        assertEquals(latest.sequence, snapshot.sequence)
        assertEquals(runId, snapshot.runId)
    }
}
