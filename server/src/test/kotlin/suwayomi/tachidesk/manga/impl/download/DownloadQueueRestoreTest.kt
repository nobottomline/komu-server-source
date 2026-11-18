package suwayomi.tachidesk.manga.impl.download

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals

/** Komu review 5: a mutation before the restore never overwrites the persisted queue. */
class DownloadQueueRestoreTest : ApplicationTest() {
    private val preferences get() = DownloadManager.sharedPreferences

    private fun persisted(): Set<String> = preferences.getStringSet(DownloadManager.DOWNLOAD_QUEUE_KEY, emptySet()).orEmpty()

    @AfterEach
    fun cleanup() =
        runBlocking {
            DownloadManager.clear()
            clearTables(ChapterTable, MangaTable)
        }

    @Test
    fun `an enqueue before the restore keeps the persisted entries`() =
        runBlocking {
            DownloadManager.clear()
            // A missing source: downloads fail at once and stay queued as errors.
            val mangaId = createLibraryManga("Queue restore fixture")
            createChapters(mangaId, 3, false)
            val (first, second, third) =
                transaction {
                    ChapterTable
                        .select(ChapterTable.id)
                        .where { ChapterTable.manga eq mangaId }
                        .orderBy(ChapterTable.sourceOrder)
                        .map { it[ChapterTable.id].value }
                }
            // The queue a previous launch saved; this launch has not restored it yet.
            preferences.edit().putStringSet(DownloadManager.DOWNLOAD_QUEUE_KEY, setOf("$first", "$second")).commit()
            DownloadManager.forgetQueueRestoreForTesting()

            DownloadManager.enqueue(DownloadManager.EnqueueInput(listOf(third)))

            assertEquals(
                setOf(first, second, third),
                DownloadManager
                    .getStatus()
                    .queue
                    .map { it.chapterId }
                    .toSet(),
            )
            withTimeout(5_000) { while ("$third" !in persisted()) delay(20) }
            assertEquals(setOf("$first", "$second", "$third"), persisted())
        }
}
