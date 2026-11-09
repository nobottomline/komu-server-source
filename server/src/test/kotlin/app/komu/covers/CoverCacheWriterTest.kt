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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    // ---- Review 5: staging lives outside the caches and the request keeps its bytes

    @Test
    fun `a whole-cache clear after staging never takes the request's bytes`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val generation = gate.generation(mangaId)
        var stagedDuringClear: File? = null
        val saved =
            CoverCacheWriter.save(gate, mangaId, generation, dir, coverA.inputStream(), "image/jpeg") { staged ->
                // The request has staged its cover; "clear cached thumbnails" runs now.
                gate.invalidateAll { dir.deleteRecursively() }
                assertTrue(staged.isFile, "the clear never deletes a staging file")
                assertFalse(staged.toPath().startsWith(dir.toPath()), "staging is outside the cache")
                stagedDuringClear = staged
            }
        assertArrayEquals(coverA, saved.stream.use { it.readAllBytes() }, "the request serves its fetched bytes")
        assertEquals("image/jpeg", saved.mimeType)
        assertFalse(saved.cached, "the clear won: nothing cached")
        assertFalse(File(dir, "$mangaId.jpg").exists())
        assertFalse(stagedDuringClear!!.exists(), "the staging file is removed afterwards")
    }

    @Test
    fun `the request keeps its bytes when its staging file vanishes`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val saved =
            CoverCacheWriter.save(gate, mangaId, gate.generation(mangaId), dir, coverA.inputStream(), "image/jpeg") { it.delete() }
        assertArrayEquals(coverA, saved.stream.use { it.readAllBytes() })
        assertFalse(saved.cached, "nothing to publish")
        assertTrue(visible(dir).isEmpty())
    }

    @Test
    fun `a cover above the memory limit is served through the handle opened on its staging file`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val saved =
            CoverCacheWriter.save(gate, mangaId, gate.generation(mangaId), dir, coverA.inputStream(), "image/jpeg", memoryLimit = 64) { staged ->
                gate.invalidateAll { dir.deleteRecursively() }
                staged.delete() // even an unlinked staging file keeps the request's bytes
            }
        assertArrayEquals(coverA, saved.stream.use { it.readAllBytes() })
        assertFalse(saved.cached)
    }

    @Test
    fun `a current save above the memory limit is published and served whole`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val saved = CoverCacheWriter.save(gate, mangaId, gate.generation(mangaId), dir, coverA.inputStream(), "image/jpeg", memoryLimit = 64)
        assertArrayEquals(coverA, saved.stream.use { it.readAllBytes() })
        assertTrue(saved.cached)
        assertArrayEquals(coverA, File(dir, "$mangaId.jpg").readBytes())
        assertTrue(CoverStaging.directory(dir).list().orEmpty().isEmpty(), "no staging file left")
    }

    @Test
    fun `orphaned staging files are removed at start-up and once they are old`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val staging = CoverStaging.directory(dir).apply { mkdirs() }
        assertEquals(dir.absoluteFile.parentFile, staging.parentFile, "beside the cache, on the same volume")
        val old = File(staging, "1.old.komu-tmp").apply { writeBytes(coverA) }
        val fresh = File(staging, "2.fresh.komu-tmp").apply { writeBytes(coverA) }
        val now = 10_000_000_000L
        old.setLastModified(now - CoverStaging.MAXIMUM_AGE_MILLIS - 1)
        fresh.setLastModified(now - 1_000)
        assertEquals(1, CoverStaging.sweep(staging, CoverStaging.MAXIMUM_AGE_MILLIS, now))
        assertFalse(old.exists())
        assertTrue(fresh.exists(), "an unregistered file is judged by age only")
        CoverStaging.sweepAtStartup(listOf(dir, File(root, "other/thumbnails")))
        assertTrue(staging.list().orEmpty().isEmpty(), "at start-up no request runs: every staged file goes")
    }

    // ---- Review 6: a running request's staging file is live, whatever its age

    /** [bytes], handing out at most [step] bytes per read and calling [onRead] with the count read so far. */
    private class SlowStream(
        private val bytes: ByteArray,
        private val step: Int = 16,
        private val onRead: (Int) -> Unit = {},
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            onRead(position)
            if (position >= bytes.size) return -1
            val n = minOf(len, step, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    private fun suspendedOverAnHour(
        dir: File,
        memoryLimit: Int,
    ) {
        val staging = CoverStaging.directory(dir)
        var stagedFile: File? = null
        val saved =
            CoverCacheWriter.save(gate, mangaId, gate.generation(mangaId), dir, coverA.inputStream(), "image/jpeg", memoryLimit) { staged ->
                stagedFile = staged
                // The write finished; the app is then suspended for over an hour...
                val now = System.currentTimeMillis()
                assertTrue(staged.setLastModified(now - 2 * CoverStaging.MAXIMUM_AGE_MILLIS))
                // ...and on resume another request stages first, its clock two hours on, so its sweep runs.
                val orphan = File(staging, "9.orphan.komu-tmp").apply { writeBytes(coverB) }
                orphan.setLastModified(now - 2 * CoverStaging.MAXIMUM_AGE_MILLIS)
                CoverStaging.resetSweepSchedule()
                CoverStaging.stage(dir, mangaId + 1, nowMillis = now + 2 * CoverStaging.MAXIMUM_AGE_MILLIS).close()
                assertFalse(orphan.exists(), "the sweep ran and removed an unregistered orphan of the same age")
                CoverStaging.sweep(staging, CoverStaging.MAXIMUM_AGE_MILLIS, now + 2 * CoverStaging.MAXIMUM_AGE_MILLIS)
                assertTrue(staged.isFile, "the sweep never deletes a running request's staging file")
                assertTrue(CoverStaging.isLive(staged))
            }
        // Only now is the answer read.
        assertArrayEquals(coverA, saved.stream.use { it.readAllBytes() }, "the request serves its bytes")
        assertTrue(saved.cached, "and its staging file was still there to publish")
        assertArrayEquals(coverA, File(dir, "$mangaId.jpg").readBytes())
        assertFalse(CoverStaging.isLive(stagedFile!!), "released when the request finished")
        assertTrue(staging.list().orEmpty().isEmpty(), "no staging file left")
    }

    @Test
    fun `a request suspended for over an hour after its write keeps its staging file and serves its bytes`() {
        suspendedOverAnHour(File(root, "thumbnails").apply { mkdirs() }, memoryLimit = CoverCacheWriter.MEMORY_LIMIT_BYTES)
    }

    @Test
    fun `a large cover suspended for over an hour after its write keeps its staging file and serves its bytes`() {
        suspendedOverAnHour(File(root, "thumbnails").apply { mkdirs() }, memoryLimit = 64)
    }

    @Test
    fun `a large cover whose staging name is unlinked during the write is still served whole`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val staging = CoverStaging.directory(dir)
        var unlinked = 0
        val image =
            SlowStream(coverA) { position ->
                // Past the in-memory head, something outside the request removes the name.
                if (position > 64 && unlinked == 0) unlinked = staging.listFiles().orEmpty().count { it.delete() }
            }
        val saved = CoverCacheWriter.save(gate, mangaId, gate.generation(mangaId), dir, image, "image/jpeg", memoryLimit = 64)
        assertEquals(1, unlinked)
        assertArrayEquals(coverA, saved.stream.use { it.readAllBytes() }, "the descriptor opened at creation keeps the bytes")
        assertFalse(saved.cached, "nothing left to publish")
        assertTrue(visible(dir).isEmpty())
    }

    @Test
    fun `sweeps running during many active requests never take their staging files`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val staging = CoverStaging.directory(dir)
        val requests = 24
        val covers = (0 until requests).map { jpeg(cropped(art(seed = 10 + it), 175, 238)) }
        val midWrite = CountDownLatch(requests)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(requests)
        try {
            val results =
                (0 until requests).map { i ->
                    pool.submit<Pair<ByteArray, Boolean>> {
                        val id = 100 + i
                        var held = false
                        val image =
                            SlowStream(covers[i]) { position ->
                                if (position >= 32 && !held) {
                                    held = true
                                    midWrite.countDown()
                                    release.await()
                                }
                            }
                        // Half are held in memory, half are served through their descriptor.
                        val limit = if (i % 2 == 0) CoverCacheWriter.MEMORY_LIMIT_BYTES else 16
                        val saved =
                            CoverCacheWriter.save(gate, id, gate.generation(id), dir, image, "image/jpeg", limit) { staged ->
                                staged.setLastModified(0)
                                CoverStaging.sweep(staging, 0, Long.MAX_VALUE)
                            }
                        saved.stream.use { it.readAllBytes() } to saved.cached
                    }
                }
            assertTrue(midWrite.await(30, TimeUnit.SECONDS), "every request is mid-write")
            repeat(20) {
                staging.listFiles().orEmpty().forEach { it.setLastModified(0) }
                CoverStaging.sweep(staging, 0, Long.MAX_VALUE)
                CoverStaging.sweepAtStartup(listOf(dir))
            }
            assertEquals(requests, staging.list().orEmpty().size, "every active staging file survived the sweeps")
            release.countDown()
            results.forEachIndexed { i, result ->
                val (served, cached) = result.get(30, TimeUnit.SECONDS)
                assertArrayEquals(covers[i], served, "request $i serves its bytes")
                assertTrue(cached, "request $i published")
                assertArrayEquals(covers[i], File(dir, "${100 + i}.jpg").readBytes())
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        assertTrue(staging.list().orEmpty().isEmpty(), "every request released its staging file")
    }

    @Test
    fun `the start-up sweep removes a previous process's leftovers and keeps a live request's file`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        val staging = CoverStaging.directory(dir).apply { mkdirs() }
        // A previous process was killed mid-request: fresh and old leftovers, never registered here.
        val leftovers =
            listOf("3.a.komu-tmp", "4.b.komu-tmp").map { name ->
                File(staging, name).apply { writeBytes(coverA) }
            }
        leftovers[1].setLastModified(System.currentTimeMillis() - 2 * CoverStaging.MAXIMUM_AGE_MILLIS)
        CoverStaging.stage(dir, mangaId).use { live ->
            live.write(coverA)
            CoverStaging.sweepAtStartup(listOf(dir))
            assertTrue(leftovers.none(File::exists), "every unregistered leftover goes, whatever its age")
            assertTrue(live.file.isFile, "a registered staging file stays")
        }
        assertTrue(staging.list().orEmpty().isEmpty())
    }

    // ---- Review 4: a cached cover vanishing between lookup and open

    @Test
    fun `a temp cover cleared between lookup and open is fetched again, never a missing file`() {
        val dir = File(root, "tmp-thumbnails").apply { mkdirs() }
        File(dir, "$mangaId.jpg").writeBytes(coverA)
        var fetched = 0
        val (served, mime) =
            runBlocking {
                CoverCacheWriter.cachedOrFetched(
                    gate,
                    mangaId,
                    gate.generation(mangaId),
                    dir.path,
                    fetcher = {
                        fetched++
                        response(coverB)
                    },
                    // The refresh's clear lands after the lookup found the file.
                    afterLookup = { found -> gate.invalidate(mangaId) { found.delete() } },
                )
            }
        assertArrayEquals(coverB, served.use { it.readAllBytes() }, "the fresh fetch answers the request")
        assertEquals("image/jpeg", mime)
        assertEquals(1, fetched)
    }

    @Test
    fun `a clear from another thread waits until the found cover is opened`() {
        val dir = File(root, "tmp-thumbnails").apply { mkdirs() }
        File(dir, "$mangaId.jpg").writeBytes(coverA)
        var clearer: Thread? = null
        val (served, _) =
            runBlocking {
                CoverCacheWriter.cachedOrFetched(
                    gate,
                    mangaId,
                    gate.generation(mangaId),
                    dir.path,
                    fetcher = { error("the found cover is served, no fetch") },
                    afterLookup = { found ->
                        clearer = Thread { gate.invalidate(mangaId) { found.delete() } }.apply { start() }
                        clearer!!.join(300)
                        assertTrue(clearer!!.isAlive, "the clear waits for the open")
                    },
                )
            }
        assertArrayEquals(coverA, served.use { it.readAllBytes() }, "the opened file is served whole")
        clearer!!.join(5_000)
        assertTrue(visible(dir).isEmpty(), "the clear ran after the open")
    }

    @Test
    fun `a library cover cleared between lookup and open is reported missing, so it is fetched`() {
        val dir = File(root, "thumbnails").apply { mkdirs() }
        File(dir, "$mangaId.png").writeBytes(png(art(seed = 5)))
        assertNull(CoverCacheWriter.openCached(gate, mangaId, dir.path) { found -> gate.invalidate(mangaId) { found.delete() } })
        assertNull(CoverCacheWriter.openCached(gate, mangaId, dir.path), "nothing cached")
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
