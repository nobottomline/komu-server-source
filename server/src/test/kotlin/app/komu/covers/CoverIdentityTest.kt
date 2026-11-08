package app.komu.covers

import app.komu.covers.SyntheticCovers.art
import app.komu.covers.SyntheticCovers.cropped
import app.komu.covers.SyntheticCovers.inspect
import app.komu.covers.SyntheticCovers.jpeg
import app.komu.covers.SyntheticCovers.png
import app.komu.covers.SyntheticCovers.scaled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class CoverIdentityTest {
    private val original = art(seed = 1)
    private val originalInspection = inspect(jpeg(original))

    @Test
    fun `a proportional thumbnail matches its original`() {
        val thumbnail = inspect(jpeg(scaled(original, 175, 263)))
        val result = CoverIdentity.compare(thumbnail, originalInspection, maxAspectDeviation = 0.05)
        assertEquals(CoverVerdictKind.ACCEPTED, result.kind)
        assertTrue(result.hashDistance!! <= 4, "distance ${result.hashDistance}")
    }

    @Test
    fun `a hard-cropped WordPress thumbnail matches through the centre crop`() {
        val thumbnail = inspect(jpeg(cropped(original, 175, 238)))
        val result = CoverIdentity.compare(thumbnail, originalInspection, maxAspectDeviation = 0.5)
        assertEquals(CoverVerdictKind.ACCEPTED, result.kind, "distance ${result.hashDistance}")
    }

    @Test
    fun `a stretched CDN thumbnail matches over the whole frame`() {
        val square = art(seed = 7, width = 338 * 2, height = 354 * 2)
        val thumbnail = inspect(jpeg(scaled(square, 285, 375)))
        val result = CoverIdentity.compare(thumbnail, inspect(png(square)), maxAspectDeviation = 0.5)
        assertEquals(CoverVerdictKind.ACCEPTED, result.kind, "distance ${result.hashDistance}")
    }

    @Test
    fun `another picture is rejected even at the same size`() {
        for (seed in 2..12) {
            val thumbnail = inspect(jpeg(scaled(art(seed), 175, 263)))
            val result = CoverIdentity.compare(thumbnail, originalInspection, maxAspectDeviation = 0.5)
            assertEquals(CoverVerdictKind.DIFFERENT_PICTURE, result.kind, "seed $seed distance ${result.hashDistance}")
        }
    }

    @Test
    fun `an original that does not add resolution is not larger`() {
        val thumbnail = inspect(jpeg(scaled(original, 285, 428)))
        // Baozi measured: a 338x354 "original" of a 285x375 thumbnail fills its frame with fewer pixels.
        val squat = inspect(jpeg(scaled(original, 338, 354)))
        assertEquals(CoverVerdictKind.NOT_LARGER, CoverIdentity.compare(thumbnail, squat, 0.5).kind)
        val smaller = inspect(jpeg(scaled(original, 207, 311)))
        assertEquals(CoverVerdictKind.NOT_LARGER, CoverIdentity.compare(thumbnail, smaller, 0.5).kind)
        val marginal = inspect(jpeg(scaled(original, 300, 450)))
        assertEquals(CoverVerdictKind.NOT_LARGER, CoverIdentity.compare(thumbnail, marginal, 0.5).kind)
    }

    @Test
    fun `resolution gain is the aspect-fill coverage of the thumbnail frame`() {
        val thumbnail = CoverInspection(285, 375, ByteArray(64 * 64))
        assertEquals(1.158, CoverIdentity.resolutionGain(thumbnail, CoverInspection(330, 440, ByteArray(64 * 64))), 0.001)
        assertEquals(0.944, CoverIdentity.resolutionGain(thumbnail, CoverInspection(338, 354, ByteArray(64 * 64))), 0.001)
    }

    @Test
    fun `a shape beyond the rule tolerance is rejected before hashing`() {
        val thumbnail = inspect(jpeg(scaled(original, 175, 263)))
        val landscape = inspect(jpeg(scaled(original, 1280, 800)))
        val result = CoverIdentity.compare(thumbnail, landscape, maxAspectDeviation = 0.5)
        assertEquals(CoverVerdictKind.ASPECT_MISMATCH, result.kind)
        assertNull(result.hashDistance)
    }

    @Test
    fun `the inspector rejects non images and reports oriented size`() {
        assertNull(ImageIoCoverImageInspector.inspect("<html>blocked</html>".toByteArray()))
        assertNull(ImageIoCoverImageInspector.inspect(ByteArray(0)))
        val inspection = inspect(png(scaled(original, 120, 180)))
        assertEquals(120, inspection.width)
        assertEquals(180, inspection.height)
    }

    @Test
    fun `the native ABI envelope decodes and rejects malformed payloads`() {
        val luma = ByteArray(CoverInspection.GRID * CoverInspection.GRID) { (it % 251).toByte() }
        val raw =
            ByteBuffer
                .allocate(8 + luma.size)
                .putInt(330)
                .putInt(440)
                .put(luma)
                .array()
        val decoded = NativeCoverImageInspector.decode(raw)
        assertNotNull(decoded)
        decoded!!
        assertEquals(330, decoded.width)
        assertEquals(440, decoded.height)
        assertTrue(decoded.luma.contentEquals(luma))
        assertNull(NativeCoverImageInspector.decode(raw.copyOf(raw.size - 1)))
        assertNull(
            NativeCoverImageInspector.decode(
                ByteBuffer
                    .allocate(8 + luma.size)
                    .putInt(0)
                    .putInt(10)
                    .array(),
            ),
        )
        assertNull(
            NativeCoverImageInspector.decode(
                ByteBuffer
                    .allocate(8 + luma.size)
                    .putInt(100_000)
                    .putInt(100_000)
                    .array(),
            ),
        )
    }

    @Test
    fun `the hash is stable for identical input`() {
        val a = CoverIdentity.phash(originalInspection.luma, CoverIdentity.FULL_FRAME)
        assertEquals(0, CoverIdentity.distance(a, CoverIdentity.phash(originalInspection.luma, CoverIdentity.FULL_FRAME)))
    }
}
