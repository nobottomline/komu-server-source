package app.komu.covers

import app.komu.covers.SyntheticCovers.art
import app.komu.covers.SyntheticCovers.cropped
import app.komu.covers.SyntheticCovers.jpeg
import app.komu.covers.SyntheticCovers.png
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FullSizeCoverServiceTest {
    @TempDir
    lateinit var root: File

    private val mangaId = 7
    private val sourceId = 42L
    private val base = "https://likemanga.in"
    private val thumbnailUrl = "$base/wp-content/uploads/images/cover-55016-175x238.jpg#thumb"
    private val originalUrl = "$base/wp-content/uploads/images/cover-55016.jpg#thumb"

    private val art = art(seed = 3)
    private val thumbnailBytes = jpeg(cropped(art, 175, 238))
    private val originalBytes = png(art)

    private var now = 1_000_000L

    private inner class Catalog : CoverCatalog {
        var subject: CoverSubject? = CoverSubject(mangaId, sourceId, base, thumbnailUrl)
        var inLibrary = true
        val temp by lazy { File(root, "tmp/thumbnails").apply { mkdirs() } }
        val downloads by lazy { File(root, "downloads/thumbnails").apply { mkdirs() } }

        override suspend fun subject(mangaId: Int) = subject?.takeIf { it.mangaId == mangaId }

        override fun servedCover(mangaId: Int) = find(if (inLibrary) downloads else temp, mangaId)

        override fun cachedCovers(mangaId: Int) = listOfNotNull(find(temp, mangaId), find(downloads, mangaId))

        private fun find(
            dir: File,
            id: Int,
        ) = dir.listFiles().orEmpty().firstOrNull { it.name.startsWith("$id.") }
    }

    private inner class Http : CoverHttp {
        val responses = mutableMapOf<String, () -> CoverFetch>()
        val calls = mutableListOf<String>()

        override suspend fun get(
            sourceId: Long,
            url: String,
            maximumBytes: Int,
        ): CoverFetch {
            calls += url
            return responses[url]?.invoke() ?: CoverFetch(404)
        }
    }

    private val catalog = Catalog()
    private val http = Http()
    private val preferences by lazy { CoverPreferences(File(root, "komu-covers/preferences.properties")) }
    private val storeRoot get() = File(root, "komu-covers/verdicts")
    private var inspector: CoverImageInspector? = ImageIoCoverImageInspector

    private fun service(
        scope: TestScope,
        store: CoverVerdictStore = CoverVerdictStore(storeRoot),
    ) = FullSizeCoverService(
        preferences = preferences,
        store = store,
        inspector = { inspector },
        http = http,
        catalog = catalog,
        scope = scope,
        nowEpochSeconds = { now },
        nowMillis = { now * 1000 },
        sourceSpacingMillis = 0,
    )

    private fun cacheThumbnail() {
        File(catalog.temp, "$mangaId.jpg").writeBytes(thumbnailBytes)
        File(catalog.downloads, "$mangaId.jpg").writeBytes(thumbnailBytes)
    }

    @Test
    fun `a legacy cached thumbnail is served at once and upgraded in the background`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val service = service(scope)

            assertEquals(CoverServeState.PENDING, service.serveState(mangaId))
            assertTrue(service.isScheduled(mangaId))
            assertTrue(http.calls.isEmpty(), "serving never waits for the network")
            assertFalse(service.schedule(mangaId), "a second request coalesces")

            scope.testScheduler.advanceUntilIdle()

            assertFalse(service.isScheduled(mangaId))
            // The original replaced both cached files; the extension follows the bytes.
            for (dir in listOf(catalog.temp, catalog.downloads)) {
                assertFalse(File(dir, "$mangaId.jpg").exists())
                assertTrue(File(dir, "$mangaId.png").readBytes().contentEquals(originalBytes))
            }
            assertEquals(listOf(originalUrl), http.calls, "the cached thumbnail is the reference: no thumbnail refetch")
            assertEquals(CoverServeState.ORIGINAL, service.serveState(mangaId))
            assertEquals(listOf(originalUrl), http.calls)
        }

    @Test
    fun `a missing original is remembered and the thumbnail stays`() =
        runTest {
            cacheThumbnail()
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            val verdict = service.verify(mangaId)
            assertEquals(CoverVerdictKind.NOT_FOUND, verdict?.kind)
            assertTrue(File(catalog.downloads, "$mangaId.jpg").readBytes().contentEquals(thumbnailBytes))
            assertEquals(CoverServeState.THUMBNAIL, service.serveState(mangaId))
            service.verify(mangaId)
            assertEquals(1, http.calls.size, "a rejection is not retried before it is due")

            now += CoverVerdict.REJECTION_RECHECK.inWholeSeconds
            service.verify(mangaId)
            assertEquals(2, http.calls.size, "rejections are rechecked after their period")
        }

    @Test
    fun `another picture behind the original URL is rejected and nothing is replaced`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, png(art(seed = 99)), "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.DIFFERENT_PICTURE, service.verify(mangaId)?.kind)
            assertTrue(File(catalog.temp, "$mangaId.jpg").readBytes().contentEquals(thumbnailBytes))
            assertFalse(File(catalog.temp, "$mangaId.png").exists())
            assertEquals(CoverServeState.THUMBNAIL, service.serveState(mangaId))
        }

    @Test
    fun `a challenge or html page is not an image`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, "<html>Just a moment</html>".toByteArray(), "text/html") }
            assertEquals(CoverVerdictKind.NOT_IMAGE, service(TestScope(StandardTestDispatcher(testScheduler))).verify(mangaId)?.kind)
        }

    @Test
    fun `network failures back off, stay pending, then give up as a thumbnail`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { throw IOException("timeout") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))

            assertEquals(CoverVerdictKind.TRANSIENT, service.verify(mangaId)?.kind)
            assertEquals(CoverServeState.PENDING, service.serveState(mangaId))
            assertFalse(service.isScheduled(mangaId), "not rescheduled before the backoff ends")
            service.verify(mangaId)
            assertEquals(1, http.calls.size)

            repeat(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS - 1) { attempt ->
                now += CoverVerdict.transientBackoff(attempt + 1).inWholeSeconds
                service.verify(mangaId)
            }
            assertEquals(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS, http.calls.size)
            assertEquals(CoverServeState.THUMBNAIL, service.serveState(mangaId), "exhausted retries settle on the thumbnail")
            assertTrue(File(catalog.temp, "$mangaId.jpg").readBytes().contentEquals(thumbnailBytes))

            // A server error is transient too.
            assertEquals(CoverVerdictKind.TRANSIENT, FullSizeCoverService.classify(503))
            assertEquals(CoverVerdictKind.TRANSIENT, FullSizeCoverService.classify(429))
            assertEquals(CoverVerdictKind.NOT_FOUND, FullSizeCoverService.classify(410))
        }

    @Test
    fun `without a decoder the thumbnail is kept and verification is retried later`() =
        runTest {
            cacheThumbnail()
            inspector = null
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.UNVERIFIABLE, service.verify(mangaId)?.kind)
            assertTrue(File(catalog.temp, "$mangaId.jpg").exists())
        }

    @Test
    fun `turning the preference off restores legacy behaviour and the cache token`() =
        runTest {
            cacheThumbnail()
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertTrue(service.preferFullSize, "on by default")
            assertEquals("o${CoverOriginalRules.VERSION}", service.cacheToken)

            service.setPreferFullSize(false)
            assertNull(service.cacheToken)
            assertEquals(CoverServeState.NOT_APPLICABLE, service.serveState(mangaId))
            assertNull(service.verify(mangaId))
            assertTrue(http.calls.isEmpty())
            // The preference survives a restart.
            assertFalse(CoverPreferences(File(root, "komu-covers/preferences.properties")).preferFullSize)
        }

    @Test
    fun `a source without a rule is final at once and never fetched`() =
        runTest {
            catalog.subject = CoverSubject(mangaId, sourceId, "https://baozimh.org", "https://c-nc-1.6wm.top/manga/x/MrfJXwLdX.webp-w320.webp")
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverServeState.THUMBNAIL, service.serveState(mangaId))
            assertFalse(service.isScheduled(mangaId))
        }

    @Test
    fun `verdicts are durable across restarts and an older rule set is re-evaluated`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            assertEquals(CoverVerdictKind.ACCEPTED, service(TestScope(StandardTestDispatcher(testScheduler))).verify(mangaId)?.kind)

            val restarted = service(TestScope(StandardTestDispatcher(testScheduler)), CoverVerdictStore(storeRoot))
            assertEquals(CoverServeState.ORIGINAL, restarted.serveState(mangaId))

            // A verdict written by an older rule set counts as absent.
            val store = CoverVerdictStore(storeRoot)
            store.put(thumbnailUrl, store.get(thumbnailUrl)!!.copy(kind = CoverVerdictKind.NOT_FOUND, rulesVersion = CoverOriginalRules.VERSION - 1))
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val upgraded = service(scope, CoverVerdictStore(storeRoot))
            File(catalog.temp, "$mangaId.png").delete()
            File(catalog.downloads, "$mangaId.png").delete()
            cacheThumbnail()
            assertEquals(CoverServeState.PENDING, upgraded.serveState(mangaId))
            scope.testScheduler.advanceUntilIdle()
            assertEquals(CoverServeState.ORIGINAL, upgraded.serveState(mangaId))
        }

    @Test
    fun `a cleared cache refills with the accepted original directly`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            service.verify(mangaId)

            val refill = service.acceptedOriginal(sourceId, base, thumbnailUrl)
            assertNotNull(refill)
            assertTrue(refill!!.bytes!!.contentEquals(originalBytes))

            // The source changed the original: the verdict is dropped, the thumbnail used.
            http.responses[originalUrl] = { CoverFetch(200, png(art(seed = 5)), "image/png") }
            assertNull(service.acceptedOriginal(sourceId, base, thumbnailUrl))
            assertNull(CoverVerdictStore(storeRoot).get(thumbnailUrl))

            assertNull(service.acceptedOriginal(sourceId, base, "$base/wp-content/uploads/other-175x238.jpg"), "no verdict, no extra fetch")
        }

    @Test
    fun `a cover refreshed to another URL during verification is not overwritten`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = {
                catalog.subject = CoverSubject(mangaId, sourceId, base, "$base/wp-content/uploads/images/new-175x238.jpg")
                CoverFetch(200, originalBytes, "image/png")
            }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.ACCEPTED, service.verify(mangaId)?.kind)
            assertTrue(File(catalog.temp, "$mangaId.jpg").readBytes().contentEquals(thumbnailBytes))
            assertFalse(File(catalog.temp, "$mangaId.png").exists())
        }

    @Test
    fun `without a cached thumbnail the reference is fetched with the source client`() =
        runTest {
            http.responses[thumbnailUrl] = { CoverFetch(200, thumbnailBytes, "image/jpeg") }
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.ACCEPTED, service.verify(mangaId)?.kind)
            assertEquals(listOf(thumbnailUrl, originalUrl), http.calls)
        }

    @Test
    fun `the queue is bounded and drops beyond its size`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val service =
                FullSizeCoverService(
                    preferences, CoverVerdictStore(storeRoot), { inspector }, http, catalog, scope,
                    nowEpochSeconds = { now }, maximumScheduled = 2,
                )
            assertTrue(service.schedule(1))
            assertTrue(service.schedule(2))
            assertFalse(service.schedule(3))
            scope.testScheduler.advanceUntilIdle()
            assertTrue(service.schedule(3))
        }
}
