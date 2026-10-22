package eu.kanade.tachiyomi.source.local

import eu.kanade.tachiyomi.source.local.filter.OrderBy
import eu.kanade.tachiyomi.source.local.image.LocalCoverManager
import eu.kanade.tachiyomi.source.local.io.LocalSourceFileSystem
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import suwayomi.tachidesk.server.ApplicationDirs
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceCatalogueTest {
    @TempDir lateinit var root: Path

    private val directories: ApplicationDirs = mockk()

    private fun filesystem(): LocalSourceFileSystem {
        every { directories.localMangaRoot } returns root.toString()
        return LocalSourceFileSystem(directories)
    }

    private fun source(files: LocalSourceFileSystem) = LocalSource(files, LocalCoverManager(files))

    @Test
    fun `corrupt epub does not hide healthy catalogue entries`() =
        runBlocking {
            File(series("Broken"), "Chapter.epub").writeText("invalid epub")
            series("Healthy")
            val page = source(filesystem()).getPopularManga(1)
            assertEquals(listOf("Broken", "Healthy"), page.mangas.map { it.url })
            assertFalse(page.hasNextPage)
        }

    @Test
    fun `catalogue pages are bounded complete ordered and overflow safe`() =
        runBlocking {
            repeat(150) { series("Series-%03d".format(it)) }
            val source = source(filesystem())
            val first = source.getPopularManga(1)
            val second = source.getPopularManga(2)
            val third = source.getPopularManga(3)
            assertEquals(LocalSource.PAGE_SIZE, first.mangas.size)
            assertTrue(first.hasNextPage)
            assertTrue(second.hasNextPage)
            assertFalse(third.hasNextPage)
            assertEquals((0..<150).map { "Series-%03d".format(it) }, (first.mangas + second.mangas + third.mangas).map { it.url })
            assertTrue(source.getPopularManga(Int.MAX_VALUE).mangas.isEmpty())
        }

    @Test
    fun `cached covers need one root enumeration and no chapter parsing`() =
        runBlocking {
            repeat(200) {
                val directory = series("Series-%03d".format(it))
                // Deliberately unsupported chapter input must never be parsed just
                // to render a title with a valid cached cover.
                File(directory, "Chapter.cbt").writeText("not opened")
            }
            val page = source(filesystem()).getPopularManga(1)
            assertEquals(LocalSource.PAGE_SIZE, page.mangas.size)
            assertTrue(page.mangas.all { it.thumbnail_url?.endsWith("cover.png") == true })
            verify(exactly = 1) { directories.localMangaRoot }
        }

    @Test
    fun `search latest and date sorting retain their semantics`() =
        runBlocking {
            val old = series("Old")
            val a = series("A")
            val b = series("B")
            old.setLastModified(1_000)
            a.setLastModified(System.currentTimeMillis() - 1_000)
            b.setLastModified(System.currentTimeMillis())
            val source = source(filesystem())
            assertEquals(listOf("B", "A"), source.getLatestUpdates(1).mangas.map { it.url })
            assertEquals(listOf("A"), source.getSearchManga(1, "a", FilterList(OrderBy.Popular())).mangas.map { it.url })
            val byDate = OrderBy.Popular().apply { state = Filter.Sort.Selection(1, true) }
            assertEquals(listOf("Old", "A", "B"), source.getSearchManga(1, "", FilterList(byDate)).mangas.map { it.url })
        }

    @Test
    fun `direct folder lookup preserves containment and literal unicode leaf names`() {
        val directory = series("Series 日本")
        val files = filesystem()
        assertEquals(directory, files.getMangaDirectory(directory.name))
        for (unsafe in listOf("", ".", "..", "../Series 日本", directory.absolutePath, "Series 日本/cover.png")) {
            assertNull(files.getMangaDirectory(unsafe))
            assertTrue(files.getFilesInMangaDirectory(unsafe).none())
        }
    }

    @Test
    fun `EPUB package metadata is retained even with a cached cover`() =
        runBlocking {
            val directory = series("Folder name")
            ZipOutputStream(File(directory, "Book.epub").outputStream()).use { zip ->
                fun entry(
                    name: String,
                    value: String,
                ) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip")
                entry(
                    "META-INF/container.xml",
                    """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                        <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """.trimIndent(),
                )
                entry(
                    "content.opf",
                    """
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:identifier id="id">fixture</dc:identifier>
                            <dc:title>Package title</dc:title><dc:creator>Fixture author</dc:creator>
                        </metadata>
                        <manifest><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest>
                        <spine><itemref idref="page"/></spine>
                    </package>
                    """.trimIndent(),
                )
                entry(
                    "page.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                        <head><title>Page</title></head><body>Fixture</body>
                    </html>
                    """.trimIndent(),
                )
            }
            val manga = source(filesystem()).getPopularManga(1).mangas.single()
            assertEquals("Folder name", manga.title)
            assertEquals("Fixture author", manga.author)
            assertTrue(manga.thumbnail_url?.endsWith("cover.png") == true)
        }

    @Test
    fun `empty catalogue and invalid page do not invent more pages`() =
        runBlocking {
            val source = source(filesystem())
            val page = source.getPopularManga(1)
            assertTrue(page.mangas.isEmpty())
            assertFalse(page.hasNextPage)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { source.getPopularManga(0) } }
            Unit
        }

    private fun series(name: String): File =
        root.resolve(name).toFile().apply {
            mkdirs()
            LocalSourceCatalogueTest::class.java.getResourceAsStream("/icon/localSource.png")!!.use {
                File(this, "cover.png").outputStream().use { output -> it.copyTo(output) }
            }
        }
}
