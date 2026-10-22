package suwayomi.tachidesk.manga.impl

import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.MangaList.processEntries
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import java.util.UUID

class MangaListSourceOrderTest : ApplicationTest() {
    @Test
    fun `reordered source pages retain wire order and existing database identities`() {
        val prefix = UUID.randomUUID().toString()
        val entries =
            listOf("A", "B").map { name ->
                SManga.create().apply {
                    title = name
                    url = "$prefix-$name"
                }
            }
        try {
            val original = MangasPage(entries, false).processEntries(LocalSource.ID).mangaList
            val reordered = MangasPage(entries.reversed(), false).processEntries(LocalSource.ID).mangaList
            assertEquals(original.reversed().map { it.id }, reordered.map { it.id })
            assertEquals(listOf("B", "A"), reordered.map { it.title })
        } finally {
            transaction { MangaTable.deleteWhere { MangaTable.url inList entries.map { it.url } } }
        }
    }
}
