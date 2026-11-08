package app.komu.covers

import app.komu.covers.SyntheticCovers.art
import app.komu.covers.SyntheticCovers.cropped
import app.komu.covers.SyntheticCovers.jpeg
import app.komu.covers.SyntheticCovers.png
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
        val temp by lazy { File(root, "tmp/thumbnails").apply { mkdirs() } }
        val downloads by lazy { File(root, "downloads/thumbnails").apply { mkdirs() } }

        override suspend fun subject(mangaId: Int) = subject?.takeIf { it.mangaId == mangaId }

        override fun cachedCovers(mangaId: Int) = listOfNotNull(find(temp, mangaId), find(downloads, mangaId))

        fun find(
            dir: File,
            id: Int = mangaId,
        ) = dir.listFiles().orEmpty().firstOrNull { it.name.startsWith("$id.") }
    }

    private inner class Http : CoverHttp {
        val responses = mutableMapOf<String, () -> CoverFetch>()
        val calls = mutableListOf<String>()
        val hosts = mutableListOf<Set<String>>()

        override suspend fun get(
            sourceId: Long,
            url: String,
            allowedHosts: Set<String>,
            maximumBytes: Int,
        ): CoverFetch {
            calls += url
            hosts += allowedHosts
            return responses[url]?.invoke() ?: CoverFetch(404)
        }
    }

    private val catalog = Catalog()
    private val http = Http()
    private val preferences by lazy { CoverPreferences(File(root, "komu-covers/preferences.properties")) }
    private val storeRoot get() = File(root, "komu-covers/verdicts")
    private var inspector: CoverImageInspector? = ImageIoCoverImageInspector
    private val publication = CoverPublicationGate()
    private var beforePublish: suspend (Int) -> Unit = {}

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
        publication = publication,
        nowEpochSeconds = { now },
        nowMillis = { now * 1000 },
        sourceSpacingMillis = 0,
        beforePublish = { beforePublish(it) },
    )

    private fun cacheThumbnail(bytes: ByteArray = thumbnailBytes) {
        File(catalog.temp, "$mangaId.jpg").writeBytes(bytes)
        File(catalog.downloads, "$mangaId.jpg").writeBytes(bytes)
    }

    /** What the thumbnail endpoint does: open the served file, then classify those bytes. */
    private suspend fun FullSizeCoverService.serveCached(): ServedCover =
        serve(
            mangaId,
            catalog.find(catalog.downloads)!!.inputStream() to "image/jpeg",
        )

    private suspend fun FullSizeCoverService.state(): CoverServeState = serveCached().state

    @Test
    fun `a legacy cached thumbnail is served at once and upgraded in the background`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val service = service(scope)

            val first = service.serveCached()
            assertEquals(CoverServeState.PENDING, first.state)
            assertArrayEquals(thumbnailBytes, first.stream.readAllBytes())
            assertTrue(service.isScheduled(mangaId))
            assertTrue(http.calls.isEmpty(), "serving never waits for the network")
            assertFalse(service.schedule(mangaId), "a second request coalesces")

            scope.testScheduler.advanceUntilIdle()

            assertFalse(service.isScheduled(mangaId))
            // The original replaced both cached files in place: one stable name per cover.
            for (dir in listOf(catalog.temp, catalog.downloads)) {
                assertEquals(listOf("$mangaId.jpg"), dir.list()!!.filter { it.startsWith("$mangaId.") })
                assertArrayEquals(originalBytes, File(dir, "$mangaId.jpg").readBytes())
            }
            assertEquals(listOf(originalUrl), http.calls, "the cached thumbnail is the reference: no thumbnail refetch")
            assertEquals(setOf("likemanga.in", "www.likemanga.in"), http.hosts.single(), "fetches are bound to the source's site")
            val served = service.serveCached()
            assertEquals(CoverServeState.ORIGINAL, served.state)
            assertEquals("image/png", served.contentType, "the type comes from the served bytes")
            assertArrayEquals(originalBytes, served.stream.readAllBytes())
            assertEquals(listOf(originalUrl), http.calls)
        }

    @Test
    fun `the state describes the bytes that were opened even when the cover is replaced meanwhile`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            val opened = catalog.find(catalog.downloads)!!.inputStream()
            // The request opened the thumbnail; the original is verified and
            // published before the request classifies what it serves.
            assertEquals(CoverVerdictKind.ACCEPTED, service.verify(mangaId)?.kind)
            val served = service.serve(mangaId, opened to "image/jpeg")
            assertArrayEquals(thumbnailBytes, served.stream.readAllBytes())
            assertEquals(CoverServeState.PENDING, served.state, "thumbnail bytes are never labelled original")
        }

    @Test
    fun `a reader that found the cover name always opens a complete image while the original is published`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            // A reader resolved the name (upstream lists the directory, then opens the path)...
            val found = catalog.find(catalog.temp)!!
            // ...the original (another image type) is published in between...
            service.verify(mangaId)
            // ...and the open still succeeds, with one complete image.
            assertArrayEquals(originalBytes, found.inputStream().use { it.readAllBytes() })
            assertEquals(listOf("$mangaId.jpg"), catalog.temp.list()!!.toList())
        }

    @Test
    fun `a missing original is remembered and the thumbnail stays`() =
        runTest {
            cacheThumbnail()
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            val verdict = service.verify(mangaId)
            assertEquals(CoverVerdictKind.NOT_FOUND, verdict?.kind)
            assertArrayEquals(thumbnailBytes, File(catalog.downloads, "$mangaId.jpg").readBytes())
            assertEquals(CoverServeState.THUMBNAIL, service.state())
            service.verify(mangaId)
            assertEquals(1, http.calls.size, "a rejection is not retried before it is due")
        }

    @Test
    fun `a rejected original is rechecked through ordinary traffic once due and then served`() =
        runTest {
            cacheThumbnail()
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val service = service(scope)
            assertEquals(CoverServeState.PENDING, service.state())
            scope.testScheduler.advanceUntilIdle()
            assertEquals(CoverVerdictKind.NOT_FOUND, CoverVerdictStore(storeRoot).get(thumbnailUrl)?.kind)
            assertEquals(CoverServeState.THUMBNAIL, service.state())
            assertFalse(service.isScheduled(mangaId), "not due yet: nothing scheduled")

            // The source publishes the original later.
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            now += CoverVerdict.REJECTION_RECHECK.inWholeSeconds - 1
            assertEquals(CoverServeState.THUMBNAIL, service.state())
            now += 1
            assertEquals(CoverServeState.PENDING, service.state(), "a due recheck is served as pending and scheduled")
            assertTrue(service.isScheduled(mangaId))
            scope.testScheduler.advanceUntilIdle()
            assertEquals(CoverServeState.ORIGINAL, service.state())
            assertEquals(1, CoverVerdictStore(storeRoot).get(thumbnailUrl)?.attempts)
        }

    @Test
    fun `another picture behind the original URL is rejected and nothing is replaced`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, png(art(seed = 99)), "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.DIFFERENT_PICTURE, service.verify(mangaId)?.kind)
            assertArrayEquals(thumbnailBytes, File(catalog.temp, "$mangaId.jpg").readBytes())
            assertEquals(CoverServeState.THUMBNAIL, service.state())
        }

    @Test
    fun `a challenge or html page is not an image`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, "<html>Just a moment</html>".toByteArray(), "text/html") }
            assertEquals(CoverVerdictKind.NOT_IMAGE, service(TestScope(StandardTestDispatcher(testScheduler))).verify(mangaId)?.kind)
        }

    @Test
    fun `a refused fetch is a final rejection and keeps the thumbnail`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { throw CoverEgressRefused("Host outside the source's cover host") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.REFUSED, service.verify(mangaId)?.kind)
            assertArrayEquals(thumbnailBytes, File(catalog.temp, "$mangaId.jpg").readBytes())
            assertEquals(CoverServeState.THUMBNAIL, service.state())
            assertEquals(CoverVerdictKind.REFUSED, FullSizeCoverService.classify(302), "a redirect without a usable location")
        }

    @Test
    fun `network failures back off, stay pending, settle on the thumbnail and are rechecked through serving`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { throw IOException("timeout") }
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val service = service(scope)

            assertEquals(CoverVerdictKind.TRANSIENT, service.verify(mangaId)?.kind)
            assertEquals(CoverServeState.PENDING, service.state())
            assertFalse(service.isScheduled(mangaId), "not rescheduled before the backoff ends")
            service.verify(mangaId)
            assertEquals(1, http.calls.size)

            repeat(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS - 1) { attempt ->
                now += CoverVerdict.transientBackoff(attempt + 1).inWholeSeconds
                assertEquals(CoverServeState.PENDING, service.state())
                scope.testScheduler.advanceUntilIdle()
            }
            assertEquals(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS, http.calls.size)
            assertEquals(CoverServeState.THUMBNAIL, service.state(), "exhausted retries settle on the thumbnail")
            assertArrayEquals(thumbnailBytes, File(catalog.temp, "$mangaId.jpg").readBytes())

            // The exhausted verdict is due again later; the next answer succeeds.
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            now += CoverVerdict.transientBackoff(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS).inWholeSeconds
            assertEquals(CoverServeState.PENDING, service.state())
            scope.testScheduler.advanceUntilIdle()
            assertEquals(CoverServeState.ORIGINAL, service.state())
            assertEquals(1, CoverVerdictStore(storeRoot).get(thumbnailUrl)?.attempts, "a final outcome resets the attempts")

            // A server error is transient too.
            assertEquals(CoverVerdictKind.TRANSIENT, FullSizeCoverService.classify(503))
            assertEquals(CoverVerdictKind.TRANSIENT, FullSizeCoverService.classify(429))
            assertEquals(CoverVerdictKind.NOT_FOUND, FullSizeCoverService.classify(410))
        }

    @Test
    fun `retried failures of different kinds count together`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            var attempt = 0
            inspector = null // UNVERIFIABLE
            http.responses[originalUrl] = {
                attempt++
                if (attempt % 2 == 0) throw IOException("timeout") else CoverFetch(200, originalBytes, "image/png")
            }
            repeat(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS) {
                service.verify(mangaId)
                now += CoverVerdict.transientBackoff(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS).inWholeSeconds
            }
            val verdict = CoverVerdictStore(storeRoot).get(thumbnailUrl)!!
            assertEquals(CoverVerdict.MAXIMUM_TRANSIENT_ATTEMPTS, verdict.attempts)
            assertTrue(verdict.isExhausted, "alternating TRANSIENT/UNVERIFIABLE does not retry forever every 15 minutes")
        }

    @Test
    fun `without a decoder the thumbnail is kept and verification is retried later`() =
        runTest {
            cacheThumbnail()
            inspector = null
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.UNVERIFIABLE, service.verify(mangaId)?.kind)
            assertArrayEquals(thumbnailBytes, File(catalog.temp, "$mangaId.jpg").readBytes())
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
            val served = service.serveCached()
            assertEquals(CoverServeState.NOT_APPLICABLE, served.state)
            assertEquals("image/jpeg", served.contentType, "upstream's answer is passed through")
            assertNull(service.verify(mangaId))
            assertTrue(http.calls.isEmpty())
            // The preference survives a restart.
            assertFalse(CoverPreferences(File(root, "komu-covers/preferences.properties")).preferFullSize)
        }

    @Test
    fun `a source without a rule is final at once and never fetched`() =
        runTest {
            catalog.subject =
                CoverSubject(mangaId, sourceId, "https://baozimh.org", "https://c-nc-1.6wm.top/manga/x/MrfJXwLdX.webp-w320.webp")
            cacheThumbnail()
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverServeState.THUMBNAIL, service.state())
            assertFalse(service.isScheduled(mangaId))
        }

    @Test
    fun `verdicts are durable across restarts and an older rule set is re-evaluated`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            assertEquals(CoverVerdictKind.ACCEPTED, service(TestScope(StandardTestDispatcher(testScheduler))).verify(mangaId)?.kind)

            val restarted = service(TestScope(StandardTestDispatcher(testScheduler)), CoverVerdictStore(storeRoot))
            assertEquals(CoverServeState.ORIGINAL, restarted.state())

            // A verdict written by an older rule set counts as absent.
            val store = CoverVerdictStore(storeRoot)
            store.put(
                thumbnailUrl,
                store.get(thumbnailUrl)!!.copy(
                    kind = CoverVerdictKind.NOT_FOUND,
                    rulesVersion =
                        CoverOriginalRules.VERSION - 1,
                ),
            )
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val upgraded = service(scope, CoverVerdictStore(storeRoot))
            cacheThumbnail()
            assertEquals(CoverServeState.PENDING, upgraded.state())
            scope.testScheduler.advanceUntilIdle()
            assertEquals(CoverServeState.ORIGINAL, upgraded.state())
        }

    @Test
    fun `an original published under an older rule set is not taken for the thumbnail and is reverted on rejection`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            service(TestScope(StandardTestDispatcher(testScheduler))).verify(mangaId)
            val store = CoverVerdictStore(storeRoot)
            store.put(thumbnailUrl, store.get(thumbnailUrl)!!.copy(rulesVersion = CoverOriginalRules.VERSION - 1))
            // The newer rule set rejects the original; the reference is the thumbnail fetched again.
            http.responses[originalUrl] = { CoverFetch(404) }
            http.responses[thumbnailUrl] = { CoverFetch(200, thumbnailBytes, "image/jpeg") }
            http.calls.clear()
            val verdict = service(TestScope(StandardTestDispatcher(testScheduler)), CoverVerdictStore(storeRoot)).verify(mangaId)
            assertEquals(CoverVerdictKind.NOT_FOUND, verdict?.kind)
            assertEquals(listOf(thumbnailUrl, originalUrl), http.calls)
            assertArrayEquals(thumbnailBytes, File(catalog.downloads, "$mangaId.jpg").readBytes(), "the cache agrees with the verdict")
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
            assertArrayEquals(originalBytes, refill!!.bytes)

            // The source changed the original: the verdict is dropped, the thumbnail used.
            http.responses[originalUrl] = { CoverFetch(200, png(art(seed = 5)), "image/png") }
            assertNull(service.acceptedOriginal(sourceId, base, thumbnailUrl))
            assertNull(CoverVerdictStore(storeRoot).get(thumbnailUrl))

            // A refused refill drops the verdict as well.
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            cacheThumbnail()
            assertEquals(CoverVerdictKind.ACCEPTED, service.verify(mangaId)?.kind)
            http.responses[originalUrl] = { throw CoverEgressRefused("Address refused") }
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
            assertArrayEquals(thumbnailBytes, File(catalog.temp, "$mangaId.jpg").readBytes())
        }

    @Test
    fun `a refresh between the URL check and the publication wins over the stale verification`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val newer = jpeg(cropped(art(seed = 11), 175, 238))
            // Deterministic interleaving: the refresh lands after the
            // verification checked the URL, before it publishes. The cover
            // URL stays the same (a restore, or a source that replaced the
            // image behind the URL), so only the generation can tell.
            beforePublish = {
                publication.invalidate(mangaId) {
                    File(catalog.temp, "$mangaId.jpg").delete()
                    File(catalog.downloads, "$mangaId.jpg").delete()
                }
                cacheThumbnail(newer) // the upstream refill after the clear
            }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.ACCEPTED, service.verify(mangaId)?.kind)
            for (dir in listOf(catalog.temp, catalog.downloads)) {
                assertArrayEquals(newer, File(dir, "$mangaId.jpg").readBytes(), "the stale original never overwrites the newer cover")
            }
            // The newer cover is verified again the next time it is shown.
            beforePublish = {}
            assertEquals(CoverServeState.PENDING, service.state())
        }

    @Test
    fun `clearing every cover cache during verification also wins`() =
        runTest {
            cacheThumbnail()
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            beforePublish = {
                publication.invalidateAll { catalog.temp.listFiles()!!.forEach(File::delete) }
                File(catalog.temp, "$mangaId.jpg").writeBytes(thumbnailBytes)
            }
            service(TestScope(StandardTestDispatcher(testScheduler))).verify(mangaId)
            assertArrayEquals(thumbnailBytes, File(catalog.temp, "$mangaId.jpg").readBytes())
            assertArrayEquals(thumbnailBytes, File(catalog.downloads, "$mangaId.jpg").readBytes())
        }

    @Test
    fun `without a cached thumbnail the reference is fetched with the source client`() =
        runTest {
            http.responses[thumbnailUrl] = { CoverFetch(200, thumbnailBytes, "image/jpeg") }
            http.responses[originalUrl] = { CoverFetch(200, originalBytes, "image/png") }
            val service = service(TestScope(StandardTestDispatcher(testScheduler)))
            assertEquals(CoverVerdictKind.ACCEPTED, service.verify(mangaId)?.kind)
            assertEquals(listOf(thumbnailUrl, originalUrl), http.calls)
            assertEquals(setOf("likemanga.in"), http.hosts.first(), "the reference fetch is bound to the thumbnail host")
        }

    @Test
    fun `the queue is bounded and drops beyond its size`() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val service =
                FullSizeCoverService(
                    preferences,
                    CoverVerdictStore(storeRoot),
                    { inspector },
                    http,
                    catalog,
                    scope,
                    nowEpochSeconds = { now },
                    maximumScheduled = 2,
                )
            assertTrue(service.schedule(1))
            assertTrue(service.schedule(2))
            assertFalse(service.schedule(3))
            scope.testScheduler.advanceUntilIdle()
            assertTrue(service.schedule(3))
        }
}
