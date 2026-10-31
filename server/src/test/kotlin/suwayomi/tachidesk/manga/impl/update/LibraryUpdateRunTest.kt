package suwayomi.tachidesk.manga.impl.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
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
        val finished =
            terminal.mangaStatusMap
                .filterKeys { it == JobStatus.COMPLETE || it == JobStatus.FAILED }
                .values
                .sumOf { it.size }
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
        waitFor {
            updater.statusDeprecated.value.runState
                ?.isTerminal == true
        }
        val latest = updater.statusDeprecated.value
        val frames = updater.record()
        val snapshot = assertNotNull(frames.firstOrNull())
        assertEquals(latest.sequence, snapshot.sequence)
        assertEquals(runId, snapshot.runId)
    }

    @Test
    fun `concurrent publications reach subscribers in sequence order and the snapshot is the last frame`() {
        val updater = Updater()
        val frames = updater.record()
        // Every reset publishes; a thousand of them from many threads race
        // to build, number and publish statuses.
        val threads =
            (0 until 8).map {
                Thread { repeat(125) { updater.reset() } }.also { it.start() }
            }
        threads.forEach { it.join() }
        waitFor { frames.size >= 1_001 }
        Thread.sleep(200)
        val sequences = frames.drop(1).map { it.sequence }
        val inversions = sequences.zipWithNext().count { (a, b) -> b <= a }
        assertEquals(0, inversions, "every subscriber sees strictly increasing sequences")
        assertEquals(frames.last().sequence, updater.statusDeprecated.value.sequence, "the snapshot is the last published status")
        assertTrue(frames.all { it.serverInstanceId != null && it.serverInstanceId == frames.first().serverInstanceId })
        assertTrue(Updater().statusDeprecated.value.serverInstanceId != frames.first().serverInstanceId, "another process, another id")
    }

    @Test
    fun `a stop before the titles are queued leaves nothing to run`() {
        val categories = category("Race fixture")
        val updater = Updater()
        val reached = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        updater.beforeEnqueue = {
            reached.complete(Unit)
            withContext(NonCancellable) { resume.await() }
        }
        val frames = updater.record()

        val runId = updater.startLibraryUpdate(categories, clear = true, forceAll = true)
        runBlocking { reached.await() }
        updater.reset()
        resume.complete(Unit)

        waitFor { frames.any { it.runId == runId && it.runState == UpdateRunState.CANCELLED } }
        Thread.sleep(1_500)
        val afterStop = frames.dropWhile { it.runState != UpdateRunState.CANCELLED }
        assertTrue(afterStop.none { it.running || it.runState == UpdateRunState.RUNNING }, "no job runs after the stop: $afterStop")
        val last = updater.statusDeprecated.value
        assertEquals(UpdateRunState.CANCELLED, last.runState)
        assertTrue(
            last.mangaStatusMap
                .filterKeys { it != JobStatus.SKIPPED }
                .values
                .all { it.isEmpty() },
            "the tracker stays empty",
        )
    }

    @Test
    fun `a job of a replaced run never changes the current run`() {
        val first = category("Replaced run title")
        val updater = Updater()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstRun: String? = null
        updater.beforeJobResult = { job ->
            if (job.runId == firstRun) {
                held.complete(Unit)
                // Survives the cancellation a newer run causes: only
                // ownership keeps its result out.
                withContext(NonCancellable) { release.await() }
            }
        }
        val frames = updater.record()
        firstRun = updater.startLibraryUpdate(first, clear = true, forceAll = true)
        runBlocking { held.await() }

        val oldTitle = first.first().id
        val second = Category.createCategory("Replacing run fixture")
        val secondTitle = createLibraryManga("Replacing run title")
        CategoryManga.addMangaToCategory(secondTitle, second)
        val secondRun =
            updater.startLibraryUpdate(listOfNotNull(Category.getCategoryById(second)), clear = true, forceAll = true)
        waitFor { frames.any { it.runId == secondRun && it.runState == UpdateRunState.COMPLETED } }
        release.complete(Unit)
        Thread.sleep(1_000)

        val last = updater.statusDeprecated.value
        assertEquals(secondRun, last.runId)
        assertEquals(UpdateRunState.COMPLETED, last.runState)
        val titles =
            last.mangaStatusMap.values
                .flatten()
                .map { it.title }
        assertEquals(listOf("Replacing run title"), titles, "the replaced run's job left no trace (category $oldTitle)")
        Category.removeCategory(second)
    }

    @Test
    fun `a delayed stop never publishes its titles under a newer run`() {
        val categories = category("Delayed stop title")
        val updater = Updater()
        val jobHeld = CompletableDeferred<Unit>()
        val releaseJob = CompletableDeferred<Unit>()
        var firstRun: String? = null
        updater.beforeJobResult = { job ->
            if (job.runId == firstRun) {
                jobHeld.complete(Unit)
                withContext(NonCancellable) { releaseJob.await() }
            }
        }
        val stopHeld = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        updater.beforeCapturedPublish = {
            stopHeld.complete(Unit)
            releaseStop.await()
        }
        val frames = updater.record()
        firstRun = updater.startLibraryUpdate(categories, clear = true, forceAll = true)
        runBlocking { jobHeld.await() }

        // Stop A, hold its publication, and let an empty run B complete.
        updater.reset()
        runBlocking { stopHeld.await() }
        val empty = Category.createCategory("Empty replacing run fixture")
        val secondRun =
            updater.startLibraryUpdate(listOfNotNull(Category.getCategoryById(empty)), clear = true, forceAll = true)
        waitFor { updater.statusDeprecated.value.let { it.runId == secondRun && it.runState == UpdateRunState.COMPLETED } }
        val completed = updater.statusDeprecated.value

        releaseStop.complete(Unit)
        releaseJob.complete(Unit)
        Thread.sleep(1_000)

        val last = updater.statusDeprecated.value
        assertEquals(secondRun, last.runId)
        assertEquals(UpdateRunState.COMPLETED, last.runState)
        assertTrue(
            frames.none { it.sequence > completed.sequence && it.runId != secondRun },
            "the stopped run publishes nothing once it is replaced: ${frames.filter { it.sequence > completed.sequence }}",
        )
        assertTrue(last.mangaStatusMap.values.all { it.isEmpty() }, "B has no titles: $last")
        assertTrue(
            frames.filter { it.runId == secondRun }.all { frame -> frame.mangaStatusMap.values.all { it.isEmpty() } },
            "no status of B carries A's titles",
        )
        Category.removeCategory(empty)
    }

    /** Collects [flow], taking its opening item and then stopping on the next one until [release]. */
    private fun <T> Flow<T>.blockedAfterFirst(
        release: CompletableDeferred<Unit>,
        delivered: CompletableDeferred<Unit>,
        latest: AtomicReference<T?>,
    ) {
        val opened = CompletableDeferred<Unit>()
        var first = true
        onEach {
            latest.set(it)
            if (first) {
                first = false
                opened.complete(Unit)
            } else {
                delivered.complete(Unit)
                release.await()
            }
        }.launchIn(scope)
        runBlocking { opened.await() }
    }

    @Test
    fun `a blocked subscriber never holds up the summary`() {
        val updater = Updater()
        val release = CompletableDeferred<Unit>()
        val statusDelivered = CompletableDeferred<Unit>()
        val updatesDelivered = CompletableDeferred<Unit>()
        val latestStatus = AtomicReference<UpdateStatus?>()
        val latestUpdates = AtomicReference<UpdateUpdates?>()
        updater.status.blockedAfterFirst(release, statusDelivered, latestStatus)
        updater.updates.blockedAfterFirst(release, updatesDelivered, latestUpdates)
        // The collectors subscribe to the shared flows right after their
        // opening item.
        Thread.sleep(200)

        repeat(200) { updater.reset() }
        val runId = updater.startLibraryUpdate(category(), clear = true, forceAll = true)

        waitFor { updater.statusDeprecated.value.let { it.runId == runId && it.runState == UpdateRunState.COMPLETED } }
        val summary = updater.statusDeprecated.value
        assertTrue(summary.sequence >= 201, "every publication reached the summary: ${summary.sequence}")
        assertTrue(statusDelivered.isCompleted && updatesDelivered.isCompleted, "both subscribers are stuck on a delivered item")

        // Once released, the subscribers catch up with the latest status.
        release.complete(Unit)
        waitFor { latestStatus.get()?.sequence == summary.sequence }
        assertEquals(runId, latestStatus.get()?.runId)
        waitFor { latestUpdates.get()?.isRunning == false }
    }

    @Test
    fun `a delta recorded while a publication takes the deltas reaches the next publication`() {
        val categories = category("Delta title")
        val title = CategoryManga.getCategoryMangaList(categories.first().id).first()
        val updater = Updater()
        val delivered = ConcurrentHashMap<Int, JobStatus>()
        val opened = CompletableDeferred<Unit>()
        updater.updates
            .onEach { delta ->
                delta.mangaUpdates.forEach { delivered[it.manga.id] = it.status }
                opened.complete(Unit)
            }.launchIn(scope)
        runBlocking { opened.await() }
        // The collector subscribes right after its opening item.
        Thread.sleep(200)

        // While the RUNNING publication holds the deltas it took, the
        // title's COMPLETE is recorded from another thread.
        var writer: Thread? = null
        updater.whileTakingDeltas = {
            if (writer == null) {
                val complete = UpdateJob(title, JobStatus.COMPLETE)
                writer =
                    Thread { runBlocking { updater.updateStatus(mangaUpdates = listOf(complete)) } }
                        .also {
                            it.start()
                            it.join(500)
                        }
            }
        }
        runBlocking { updater.updateStatus(immediate = true, mangaUpdates = listOf(UpdateJob(title, JobStatus.RUNNING))) }

        waitFor(5_000) { delivered[title.id] == JobStatus.COMPLETE }
        assertEquals(JobStatus.COMPLETE, delivered[title.id], "the final delta is delivered, never cleared unseen")
    }
}
