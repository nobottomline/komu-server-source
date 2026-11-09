package suwayomi.tachidesk.manga.impl.download

import app.komu.covers.FullSizeCovers
import app.komu.covers.SyntheticCovers.art
import app.komu.covers.SyntheticCovers.cropped
import app.komu.covers.SyntheticCovers.jpeg
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.ThumbnailFileProvider
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The library cover path answers every request with the bytes it fetched,
 * even when a refresh overtook the fetch and the gate kept them out of the
 * cache (ADR-0084 Review 3, G1). Runs through `Manga.getMangaThumbnail`, the
 * thumbnail endpoint's entry point, with a stub source reading a loopback
 * fixture whose handler refreshes the cover before it answers. Lives beside
 * the other `ApplicationTest`s: it starts the shared test application, which
 * `CefTest` (run before this package) must not find started.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibraryCoverDownloadTest : ApplicationTest() {
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val coverA = jpeg(cropped(art(seed = 11), 175, 238))
    private val requests = AtomicInteger()

    @Volatile
    private var duringRequest: () -> Unit = {}

    private val fixture: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            createContext("/cover.jpg") { exchange ->
                requests.incrementAndGet()
                duringRequest()
                exchange.responseHeaders.add("Content-Type", "image/jpeg")
                exchange.sendResponseHeaders(200, coverA.size.toLong())
                exchange.responseBody.use { it.write(coverA) }
            }
            start()
        }

    private fun libraryManga(): Int {
        val id = createLibraryManga("COVER_${System.nanoTime()}")
        transaction {
            MangaTable.update({ MangaTable.id eq id }) {
                it[thumbnail_url] = "http://127.0.0.1:${fixture.address.port}/cover.jpg"
            }
        }
        // The test data root outlives a run and ids restart with the tables:
        // no cover of an earlier run may answer for this one.
        coverFiles(id).forEach(File::delete)
        return id
    }

    private fun coverFiles(mangaId: Int) =
        listOf(applicationDirs.thumbnailDownloadsRoot, applicationDirs.tempThumbnailCacheRoot)
            .flatMap { File(it).listFiles().orEmpty().toList() }
            .filter { it.name.startsWith("$mangaId.") || it.name.startsWith(".$mangaId.") }

    private fun cachedFiles(mangaId: Int) = coverFiles(mangaId).map { it.name }

    @Test
    fun `a refresh during the library download still answers the request with the fetched cover`() {
        val mangaId = libraryManga()
        // The metadata refresh clears the cover (and bumps its generation)
        // after the download captured it and before the answer arrives.
        duringRequest = { Manga.clearThumbnail(mangaId) }

        val (stream, mime) = runBlocking { Manga.getMangaThumbnail(mangaId) }

        assertArrayEquals(coverA, stream.use { it.readAllBytes() }, "the request gets the fetched bytes")
        assertEquals("image/jpeg", mime)
        assertEquals(emptyList<String>(), cachedFiles(mangaId), "the overtaken download cached nothing")
        assertEquals(1, requests.get())

        // Without a refresh the next request caches the cover and then serves it from the cache.
        duringRequest = {}
        val (again, _) = runBlocking { Manga.getMangaThumbnail(mangaId) }
        assertArrayEquals(coverA, again.use { it.readAllBytes() })
        assertTrue("$mangaId.jpg" in File(applicationDirs.thumbnailDownloadsRoot).list().orEmpty(), "the library copy is cached")
        val (cached, _) = runBlocking { Manga.getMangaThumbnail(mangaId) }
        assertArrayEquals(coverA, cached.use { it.readAllBytes() })
        assertEquals(2, requests.get(), "the third request is answered from the cache")
    }

    @Test
    fun `an overtaken library download reports that nothing was cached`() {
        val mangaId = libraryManga()
        val provider =
            ThumbnailFileProvider(mangaId) { id, _ ->
                FullSizeCovers.invalidate(id) {}
                coverA.inputStream() to "image/jpeg"
            }

        assertFalse(runBlocking { provider.download().execute() }, "an overtaken download is not reported as cached")
        assertEquals(emptyList<String>(), cachedFiles(mangaId))
        val (stream, _) = runBlocking { provider.getOrDownloadImage() }
        assertArrayEquals(coverA, stream.use { it.readAllBytes() })
        assertEquals(emptyList<String>(), cachedFiles(mangaId))
    }

    @AfterEach
    fun tearDown() {
        clearTables(MangaTable)
    }

    @org.junit.jupiter.api.AfterAll
    fun stopFixture() {
        fixture.stop(0)
    }
}
