package suwayomi.tachidesk.manga.impl

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.dataclass.PAGINATION_FACTOR
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.database.migration.M0071_IndexRecentChapters
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentChaptersPaginationTest : ApplicationTest() {
    @AfterEach
    fun cleanup() = clearTables(ChapterTable, MangaTable)

    private fun fixture(count: Int): Int {
        val mangaId = createLibraryManga("Recent chapter fixture")
        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) { it[inLibraryAt] = 100 }
            // Synthetic H2 fixture: many equal discovery timestamps and old upload dates.
            exec(
                """
                INSERT INTO chapter (url, name, source_order, manga, fetched_at, date_upload)
                SELECT CONCAT('chapter-', X), CONCAT('Chapter ', X), X, $mangaId, 1000 + FLOOR(X / 10), 1
                FROM SYSTEM_RANGE(1, $count)
                """.trimIndent(),
            )
        }
        return mangaId
    }

    @Test
    fun `bounded pages preserve complete ordering and discovery semantics with equal timestamps`() {
        val mangaId = fixture(1027)
        transaction {
            // Not recent: it existed when this manga entered the library.
            exec("INSERT INTO chapter (url, name, source_order, manga, fetched_at) VALUES ('old', 'Old', 99999, $mangaId, 100)")
        }
        val expected =
            transaction {
                (ChapterTable innerJoin MangaTable)
                    .select(ChapterTable.id)
                    .where { (MangaTable.inLibrary eq true) and (ChapterTable.fetchedAt greater MangaTable.inLibraryAt) }
                    .orderBy(ChapterTable.fetchedAt to SortOrder.DESC, ChapterTable.id to SortOrder.DESC)
                    .map { it[ChapterTable.id].value }
            }
        val actual = mutableListOf<Int>()
        var page = 0
        do {
            val response = Chapter.getRecentChapters(page++)
            assertTrue(response.page.size <= PAGINATION_FACTOR)
            actual += response.page.map { it.chapter.id }
        } while (response.hasNextPage)
        assertEquals(expected, actual)
        assertEquals(1027, actual.size)
        assertEquals(actual.size, actual.toSet().size)
        assertEquals(Chapter.getRecentChapters(1), Chapter.getRecentChapters(1), "Retry is deterministic without mutations")
    }

    @Test
    fun `large fixture serves first middle and final windows without changing response shape`() {
        listOf(1_000, 10_000, 100_000).forEach { count ->
            fixture(count)
            val finalPage = count / PAGINATION_FACTOR - 1
            listOf(0, finalPage / 2, finalPage).forEach { number ->
                val response = Chapter.getRecentChapters(number)
                assertEquals(PAGINATION_FACTOR, response.page.size)
                assertEquals(number != finalPage, response.hasNextPage)
                assertEquals(
                    count - number * PAGINATION_FACTOR,
                    response.page
                        .first()
                        .chapter.index,
                )
            }
            assertTrue(Chapter.getRecentChapters(finalPage + 1).page.isEmpty())
            assertFalse(Chapter.getRecentChapters(Int.MAX_VALUE).hasNextPage)
            if (count != 100_000) clearTables(ChapterTable, MangaTable)
        }
        val plan =
            transaction {
                exec(
                    """
                    EXPLAIN SELECT chapter.id FROM chapter INNER JOIN manga ON chapter.manga = manga.id
                    WHERE chapter.id IN (
                        SELECT chapter.id FROM chapter WHERE EXISTS (
                            SELECT manga.id FROM manga WHERE manga.id = chapter.manga
                            AND manga.in_library = TRUE AND chapter.fetched_at > manga.in_library_at
                        ) ORDER BY chapter.fetched_at DESC, chapter.id DESC FETCH FIRST 51 ROWS ONLY
                    ) ORDER BY chapter.fetched_at DESC, chapter.id DESC
                    """.trimIndent(),
                    explicitStatementType = StatementType.SELECT,
                ) { result ->
                    result.next()
                    result.getString(1)
                }!!
            }
        println("Recent chapter 100k query plan: $plan")
        assertTrue(plan.contains("FETCH FIRST 51 ROWS ONLY"))
        assertTrue(plan.contains("IDX_CHAPTER_RECENT"), "H2 should use the ordered discovery index: $plan")
    }

    @Test
    fun `cursor preserves remaining chapters across insert deletion and deterministic retry`() {
        val mangaId = fixture(127)
        val first = Chapter.getRecentChapters(0)
        assertTrue(first.supportsCursorPagination)
        val cursor = first.page.last().chapter
        val originalRemaining = mutableListOf<Int>()
        var page = 1
        do {
            val next = Chapter.getRecentChapters(page++)
            originalRemaining += next.page.map { it.chapter.id }
        } while (next.hasNextPage)
        val deletedId = originalRemaining[5]
        transaction {
            ChapterTable.deleteWhere { ChapterTable.id eq deletedId }
            // Both insertions sort ahead of the already-consumed cursor, even the
            // equal-timestamp chapter with its newer ID. Neither shifts later pages.
            exec("INSERT INTO chapter (url,name,source_order,manga,fetched_at) VALUES ('new','New',999,$mangaId,999999)")
            exec("INSERT INTO chapter (url,name,source_order,manga,fetched_at) VALUES ('tie','Tie',1000,$mangaId,${cursor.fetchedAt})")
        }
        val remaining = mutableListOf<Int>()
        var before = cursor
        do {
            val next = Chapter.getRecentChapters(0, before.fetchedAt, before.id)
            assertEquals(next, Chapter.getRecentChapters(777, before.fetchedAt, before.id), "Cursor overrides OFFSET")
            remaining += next.page.map { it.chapter.id }
            if (next.page.isNotEmpty()) before = next.page.last().chapter
        } while (next.hasNextPage)
        assertEquals(originalRemaining.filter { it != deletedId }, remaining)
        assertEquals(remaining.size, remaining.toSet().size)
        assertEquals(
            999,
            Chapter
                .getRecentChapters(0)
                .page
                .first()
                .chapter.index,
        )
    }

    @Test
    fun `cursor traverses 100k equal timestamps with complete parity`() {
        fixture(100_000)
        transaction { ChapterTable.update { it[fetchedAt] = 2_000_000_000L } }
        val expected =
            transaction {
                ChapterTable
                    .select(
                        ChapterTable.id,
                    ).orderBy(ChapterTable.id to SortOrder.DESC)
                    .map { it[ChapterTable.id].value }
            }
        val actual = mutableListOf<Int>()
        var next = Chapter.getRecentChapters(0)
        while (true) {
            assertTrue(next.supportsCursorPagination)
            actual += next.page.map { it.chapter.id }
            if (!next.hasNextPage) break
            val cursor = next.page.last().chapter
            next = Chapter.getRecentChapters(0, cursor.fetchedAt, cursor.id)
            assertTrue(
                next.page.isEmpty() || next.page
                    .first()
                    .chapter.id < cursor.id,
                "Cursor must advance",
            )
        }
        assertEquals(expected, actual)
        val plan =
            transaction {
                exec(
                    "EXPLAIN ANALYZE SELECT id FROM chapter WHERE fetched_at = 2000000000 AND id < ${expected[99_900]} ORDER BY fetched_at DESC,id DESC FETCH FIRST 51 ROWS ONLY",
                    explicitStatementType = StatementType.SELECT,
                ) { result ->
                    result.next()
                    result.getString(1)
                }!!
            }
        println("Recent chapter deep cursor query plan: $plan")
        assertTrue(plan.contains("IDX_CHAPTER_RECENT"))
        val scanned = Regex("scanCount: ([0-9]+)").find(plan)!!.groupValues[1].toInt()
        assertTrue(scanned <= 52, "Deep equal-timestamp range must seek to its ID bound: $plan")
        val fullPlan =
            transaction {
                val eligible = "EXISTS (SELECT manga.id FROM manga WHERE manga.id=chapter.manga AND manga.in_library=TRUE AND chapter.fetched_at>manga.in_library_at)"
                exec(
                    """
                    EXPLAIN ANALYZE SELECT chapter.id FROM chapter INNER JOIN manga ON manga.id=chapter.manga
                    WHERE chapter.id IN (
                        SELECT recent_same_time.id FROM (
                            SELECT chapter.id FROM chapter WHERE $eligible
                            AND chapter.fetched_at=2000000000 AND chapter.id<${expected[99_900]}
                            ORDER BY chapter.fetched_at DESC,chapter.id DESC FETCH FIRST 51 ROWS ONLY
                        ) recent_same_time
                        UNION ALL
                        SELECT recent_older.id FROM (
                            SELECT chapter.id FROM chapter WHERE $eligible AND chapter.fetched_at<=1999999999
                            ORDER BY chapter.fetched_at DESC,chapter.id DESC FETCH FIRST 51 ROWS ONLY
                        ) recent_older
                    ) ORDER BY chapter.fetched_at DESC,chapter.id DESC FETCH FIRST 51 ROWS ONLY
                    """.trimIndent(),
                    explicitStatementType = StatementType.SELECT,
                ) { result ->
                    result.next()
                    result.getString(1)
                }!!
            }
        println("Recent chapter deep cursor hydration plan: $fullPlan")
        val maxScan = Regex("scanCount: ([0-9]+)").findAll(fullPlan).maxOf { it.groupValues[1].toInt() }
        assertTrue(maxScan <= 104, "No node should rescan the 100k fixture: $fullPlan")
    }

    @Test
    fun `partial invalid or absent cursor preserves explicit validation and legacy pages`() {
        assertFailsWith<IllegalArgumentException> { Chapter.getRecentChapters(0, 1, null) }
        assertFailsWith<IllegalArgumentException> { Chapter.getRecentChapters(0, null, 1) }
        assertFailsWith<IllegalArgumentException> { Chapter.getRecentChapters(0, -1, 1) }
        assertFailsWith<IllegalArgumentException> { Chapter.getRecentChapters(0, 1, 0) }
        assertFalse(Chapter.getRecentChapters(0, 0, 1).hasNextPage)
    }

    @Test
    fun `index migration is additive and idempotent`() {
        fixture(53)
        val before = Chapter.getRecentChapters(0)
        transaction {
            repeat(2) { exec(M0071_IndexRecentChapters().sql) }
        }
        assertEquals(before, Chapter.getRecentChapters(0))
        assertFalse(Chapter.getRecentChapters(1).hasNextPage)
        assertEquals(3, Chapter.getRecentChapters(1).page.size)
    }

    @Test
    fun `empty and invalid page requests cannot overflow offset arithmetic`() {
        assertTrue(Chapter.getRecentChapters(0).page.isEmpty())
        assertFalse(Chapter.getRecentChapters(Int.MAX_VALUE).hasNextPage)
        assertFailsWith<IllegalArgumentException> { Chapter.getRecentChapters(-1) }
    }
}
