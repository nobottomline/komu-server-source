package app.komu.covers

import app.komu.covers.SyntheticCovers.art
import app.komu.covers.SyntheticCovers.cropped
import app.komu.covers.SyntheticCovers.jpeg
import app.komu.covers.SyntheticCovers.png
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Every cover-cache write goes through the publication gate (ADR-0084 Review 2). */
class CoverCacheWriterTest {
    @TempDir
    lateinit var root: File

    private val gate = CoverPublicationGate()
    private val mangaId = 7
    private val coverA = jpeg(cropped(art(seed = 1), 175, 238))
    private val coverB = jpeg(cropped(art(seed = 2), 175, 238))

    private fun response(bytes: ByteArray) =
        Response
            .Builder()
            .request(Request.Builder().url("https://cdn.test/a.jpg").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody("image/jpeg".toMediaType()))
            .build()

    private fun clear(dir: File) =
        gate.invalidate(mangaId) {
            dir.listFiles()!!.filter { it.name.startsWith("$mangaId.") }.forEach(File::delete)
        }

    private fun visible(dir: File) = dir.list()!!.sorted()

    @Test
    fun `a download of A that started before the refresh to B never overwrites B`() {
        val dir = File(root, "thumbnails")
        // A's fetch begins (generation captured before the cover URL is read)...
        val (served, _) =
            runBlocking {
                CoverCacheWriter.cachedOrFetched(gate, mangaId, gate.generation(mangaId), dir.path) {
                    // ...the refresh to B clears the cache and B is cached meanwhile...
                    clear(dir)
                    CoverCacheWriter.save(gate, mangaId, gate.generation(mangaId), dir, coverB.inputStream(), "image/jpeg").stream.close()
                    // ...then A's answer arrives.
                    response(coverA)
                }
            }
        assertArrayEquals(coverA, served.use { it.readAllBytes() }, "A still answers its own request")
        assertArrayEquals(coverB, File(dir, "$mangaId.jpg").readBytes(), "the cache keeps B")
        assertEquals(listOf("$mangaId.jpg"), visible(dir), "no staging file is left behind")
    }

    @Test
    fun `a current fetch is cached under the name its bytes declare`() {
        val dir = File(root, "thumbnails")
        val (served, mime) =
            runBlocking {
                CoverCacheWriter.cachedOrFetched(
                    gate,
                    mangaId,
                    gate.generation(mangaId),
                    dir.path,
                ) { response(png(art(seed = 3))) }
            }
        served.close()
        assertEquals("image/png", mime)
        assertEquals(listOf("$mangaId.png"), visible(dir))
        // The next request is answered from the cache, no fetch.
        val (again, _) =
            runBlocking {
                CoverCacheWriter.cachedOrFetched(
                    gate,
                    mangaId,
                    gate.generation(mangaId),
                    dir.path,
                ) { error("no fetch") }
            }
        again.close()
    }

    @Test
    fun `a whole-cache clear during the download also wins`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val generation = gate.generation(mangaId)
        gate.invalidateAll { dir.listFiles()!!.forEach(File::delete) }
        val saved = CoverCacheWriter.save(gate, mangaId, generation, dir, coverA.inputStream(), "image/jpeg")
        assertArrayEquals(coverA, saved.stream.use { it.readAllBytes() }, "the fetched bytes still answer the request")
        assertEquals(false, saved.cached)
        assertTrue(visible(dir).isEmpty())
    }
}
