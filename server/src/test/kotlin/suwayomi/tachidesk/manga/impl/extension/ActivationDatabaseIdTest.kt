package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.global.impl.GlobalMeta
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupGlobalMetaHandler
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Komu review 5 P3: the database's own id is created once and never copied by a backup. */
class ActivationDatabaseIdTest : ApplicationTest() {
    @AfterEach
    fun cleanup() = clearTables(GlobalMetaTable)

    @Test
    fun `the id is created once and then reported as existing`() {
        clearTables(GlobalMetaTable)
        val (created, existedBefore) = ActivationDatabaseId.getOrCreate()
        assertFalse(existedBefore)
        val (again, existed) = ActivationDatabaseId.getOrCreate()
        assertTrue(existed)
        assertEquals(created, again)
    }

    @Test
    fun `a backup neither carries nor replaces the id`() {
        clearTables(GlobalMetaTable)
        val (id, _) = ActivationDatabaseId.getOrCreate()
        GlobalMeta.modifyMeta("client.key", "kept")
        BackupGlobalMetaHandler.restore(mapOf(ActivationDatabaseIdentity.DATABASE_ID_KEY to "foreign", "client.other" to "x"))
        assertEquals(id to true, ActivationDatabaseId.getOrCreate())
        assertEquals("x", GlobalMeta.getMetaMap()["client.other"])
    }
}
