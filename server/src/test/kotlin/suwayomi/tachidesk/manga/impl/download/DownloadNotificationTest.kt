package suwayomi.tachidesk.manga.impl.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.download.model.DownloadState
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadNotificationTest : ApplicationTest() {
    @AfterEach
    fun cleanup() =
        runBlocking {
            DownloadManager.clear()
            clearTables(ChapterTable, MangaTable)
        }

    @Test
    fun `finishing a burst with no page progress retains every terminal chapter update`() =
        runBlocking {
            val mangaId = createLibraryManga("Local completion fixture")
            transaction { MangaTable.update({ MangaTable.id eq mangaId }) { it[sourceReference] = 0 } }
            createChapters(mangaId, 20, false)
            val ids =
                transaction {
                    ChapterTable
                        .select(
                            ChapterTable.id,
                        ).where { ChapterTable.manga eq mangaId }
                        .map { it[ChapterTable.id].value }
                }
            val terminal =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5000) {
                        DownloadManager.updates.first { update ->
                            update.updates.count { it.type == DownloadUpdateType.FINISHED && it.downloadQueueItem.chapterId in ids } ==
                                ids.size
                        }
                    }
                }
            DownloadManager.enqueue(DownloadManager.EnqueueInput(ids))
            assertEquals(20, terminal.await().updates.count { it.type == DownloadUpdateType.FINISHED })
            assertTrue(DownloadManager.getStatus().queue.isEmpty())
        }

    @Test
    fun `pause before the capacity window prevents starting and resume publishes terminal failure`() =
        runBlocking {
            val mangaId = createLibraryManga("Missing source fixture")
            createChapters(mangaId, 1, false)
            val chapterId =
                transaction {
                    ChapterTable
                        .select(ChapterTable.id)
                        .where { ChapterTable.manga eq mangaId }
                        .single()[ChapterTable.id]
                        .value
                }
            DownloadManager.enqueue(DownloadManager.EnqueueInput(listOf(chapterId)))
            DownloadManager.stop()
            delay(1200)
            assertEquals(
                DownloadState.Queued,
                DownloadManager
                    .getStatus()
                    .queue
                    .single()
                    .state,
            )
            val terminal =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5000) {
                        DownloadManager.updates.first { update ->
                            update.updates.any { it.type == DownloadUpdateType.ERROR && it.downloadQueueItem.chapterId == chapterId }
                        }
                    }
                }
            // A missing source fails immediately. Its final notification must still arm
            // the sampled subscription even when no page progress follows it.
            DownloadManager.start()
            assertEquals(
                DownloadUpdateType.ERROR,
                terminal
                    .await()
                    .updates
                    .single { it.downloadQueueItem.chapterId == chapterId }
                    .type,
            )
        }
}
