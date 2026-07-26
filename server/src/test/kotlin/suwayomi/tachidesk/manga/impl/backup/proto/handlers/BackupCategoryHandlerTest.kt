package suwayomi.tachidesk.manga.impl.backup.proto.handlers

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.Category.DEFAULT_CATEGORY_ID
import suwayomi.tachidesk.manga.impl.Category.DEFAULT_CATEGORY_NAME
import suwayomi.tachidesk.manga.impl.backup.proto.models.BackupCategory
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupCategoryHandlerTest : ApplicationTest() {
    @BeforeEach
    fun setUpDefaultCategory() {
        clearCategoryState()
        transaction {
            CategoryTable.insert {
                it[id] = EntityID(DEFAULT_CATEGORY_ID, CategoryTable)
                it[name] = DEFAULT_CATEGORY_NAME
                it[order] = 0
                it[isDefault] = true
            }
        }
    }

    @AfterEach
    fun tearDown() {
        clearCategoryState()
    }

    @Test
    fun `restore reuses hidden synthetic default category`() {
        val defaultUid = 5_998_447_947_699_506_036L
        val mapping =
            BackupCategoryHandler.restore(
                listOf(
                    BackupCategory(
                        name = DEFAULT_CATEGORY_NAME,
                        order = 0,
                        uid = defaultUid,
                    ),
                ),
            )

        assertEquals(DEFAULT_CATEGORY_ID, mapping[0])
        transaction {
            val categories = CategoryTable.selectAll().toList()
            assertEquals(1, categories.size)
            assertEquals(DEFAULT_CATEGORY_ID, categories.single()[CategoryTable.id].value)
            assertEquals(defaultUid, categories.single()[CategoryTable.uid])
            assertTrue(categories.single()[CategoryTable.isDefault])
        }
    }

    @Test
    fun `repeated restore keeps unique category orders and identities`() {
        val backup =
            listOf(
                BackupCategory(
                    name = DEFAULT_CATEGORY_NAME,
                    order = 0,
                    uid = 101L,
                ),
                BackupCategory(
                    name = "Reading",
                    order = 1,
                    uid = 202L,
                ),
            )

        val firstMapping = BackupCategoryHandler.restore(backup)
        val secondMapping = BackupCategoryHandler.restore(backup)

        assertEquals(firstMapping, secondMapping)
        assertEquals(DEFAULT_CATEGORY_ID, secondMapping[0])
        assertFalse(secondMapping[1] == DEFAULT_CATEGORY_ID)
        transaction {
            val categories = CategoryTable.selectAll().toList()
            assertEquals(2, categories.size)
            assertEquals(setOf(0, 1), categories.map { it[CategoryTable.order] }.toSet())
            assertEquals(2, categories.map { it[CategoryTable.uid] }.distinct().size)
        }
    }

    private fun clearCategoryState() {
        transaction {
            CategoryMangaTable.deleteAll()
            MangaTable.deleteAll()
            CategoryTable.deleteAll()
        }
    }
}
