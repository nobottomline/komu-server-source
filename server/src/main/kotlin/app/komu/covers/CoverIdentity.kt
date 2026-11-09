/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Why a candidate original was or was not taken. */
enum class CoverVerdictKind(
    val isFinal: Boolean,
) {
    ACCEPTED(true),

    /** No rule maps the thumbnail URL to an original. */
    NO_RULE(true),

    /** The original URL answered 404/410 or another client error. */
    NOT_FOUND(true),

    /** The response was not a decodable image. */
    NOT_IMAGE(true),

    /** The original does not cover the thumbnail's frame with more pixels. */
    NOT_LARGER(true),

    /** The shape differs beyond the rule's tolerance. */
    ASPECT_MISMATCH(true),

    /** The perceptual hash says it is another picture. */
    DIFFERENT_PICTURE(true),

    /**
     * The fetch was refused by [CoverEgress]: a redirect off the source's
     * cover host, a downgrade to http, a non-public address or too many hops.
     */
    REFUSED(true),

    /**
     * The original is another image format than the cached cover; the
     * cache keeps one name per cover and readers type it by extension.
     */
    FORMAT_MISMATCH(true),

    /** Network error, timeout, server error or challenge: retried later. */
    TRANSIENT(false),

    /** This runtime cannot decode images to verify. */
    UNVERIFIABLE(false),
}

/** Outcome of comparing a thumbnail with its candidate original. */
data class CoverComparison(
    val kind: CoverVerdictKind,
    val resolutionGain: Double? = null,
    val aspectDeviation: Double? = null,
    val hashDistance: Int? = null,
)

/**
 * Decides whether a candidate is the same picture as the thumbnail, only
 * larger (ADR-0084 §Verification):
 *
 * 1. it covers the thumbnail's frame (aspect-fill) with at least
 *    [MINIMUM_RESOLUTION_GAIN] times the linear resolution;
 * 2. its aspect ratio is within the rule's tolerance of the thumbnail's;
 * 3. a 63-bit DCT perceptual hash of the thumbnail matches the candidate's,
 *    compared over the whole frame (proportional or stretched resize) and
 *    over the centre crop to the thumbnail's aspect (WordPress hard crop);
 *    the closer of the two must be within [MAXIMUM_HASH_DISTANCE].
 */
object CoverIdentity {
    /** Below this the original adds too little detail to be worth a fetch. */
    const val MINIMUM_RESOLUTION_GAIN = 1.15

    /**
     * Calibrated on live pairs (ADR-0084 §Measurements): genuine pairs
     * measure 0–6, distinct covers 14 and above.
     */
    const val MAXIMUM_HASH_DISTANCE = 10

    private const val HASH_GRID = 32

    fun compare(
        thumbnail: CoverInspection,
        candidate: CoverInspection,
        maxAspectDeviation: Double,
    ): CoverComparison {
        val gain = resolutionGain(thumbnail, candidate)
        if (gain < MINIMUM_RESOLUTION_GAIN) return CoverComparison(CoverVerdictKind.NOT_LARGER, resolutionGain = gain)
        val deviation = abs(candidate.aspect - thumbnail.aspect) / thumbnail.aspect
        if (deviation > maxAspectDeviation) {
            return CoverComparison(CoverVerdictKind.ASPECT_MISMATCH, resolutionGain = gain, aspectDeviation = deviation)
        }
        val reference = phash(thumbnail.luma, FULL_FRAME)
        val whole = distance(reference, phash(candidate.luma, FULL_FRAME))
        val cropped = distance(reference, phash(candidate.luma, centreCrop(candidate.aspect, thumbnail.aspect)))
        val best = min(whole, cropped)
        val kind = if (best <= MAXIMUM_HASH_DISTANCE) CoverVerdictKind.ACCEPTED else CoverVerdictKind.DIFFERENT_PICTURE
        return CoverComparison(kind, resolutionGain = gain, aspectDeviation = deviation, hashDistance = best)
    }

    /**
     * Linear resolution the candidate gives the thumbnail's frame when it
     * fills it (aspect-fill): `min(cw / tw, ch / th)`.
     */
    fun resolutionGain(
        thumbnail: CoverInspection,
        candidate: CoverInspection,
    ): Double =
        min(
            candidate.width.toDouble() / thumbnail.width,
            candidate.height.toDouble() / thumbnail.height,
        )

    // ---------------------------------------------------------------- hash

    /** Normalised rectangle (x, y, width, height) of a picture. */
    internal data class Rect(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    internal val FULL_FRAME = Rect(0.0, 0.0, 1.0, 1.0)

    /** The centre crop of a picture of [sourceAspect] to [targetAspect]. */
    internal fun centreCrop(
        sourceAspect: Double,
        targetAspect: Double,
    ): Rect =
        if (sourceAspect > targetAspect) {
            val width = targetAspect / sourceAspect
            Rect((1 - width) / 2, 0.0, width, 1.0)
        } else {
            val height = sourceAspect / targetAspect
            Rect(0.0, (1 - height) / 2, 1.0, height)
        }

    /** 63-bit pHash of [rect] of a luma grid, low 8x8 DCT minus DC. */
    internal fun phash(
        luma: ByteArray,
        rect: Rect,
    ): Long {
        val block = resample(luma, rect)
        val coefficients = DoubleArray(64)
        for (u in 0 until 8) {
            for (v in 0 until 8) {
                var sum = 0.0
                for (y in 0 until HASH_GRID) {
                    val rowWeight = dct[v][y]
                    for (x in 0 until HASH_GRID) {
                        sum += dct[u][x] * rowWeight * block[y * HASH_GRID + x]
                    }
                }
                coefficients[v * 8 + u] = sum
            }
        }
        val low = coefficients.copyOfRange(1, 64)
        val median = low.sorted()[low.size / 2] // 63 values: the middle one
        var hash = 0L
        low.forEachIndexed { index, value -> if (value > median) hash = hash or (1L shl index) }
        return hash
    }

    internal fun distance(
        a: Long,
        b: Long,
    ): Int = java.lang.Long.bitCount(a xor b)

    private val dct: Array<DoubleArray> =
        Array(HASH_GRID) { k ->
            DoubleArray(HASH_GRID) { n ->
                val scale = if (k == 0) sqrt(1.0 / HASH_GRID) else sqrt(2.0 / HASH_GRID)
                scale * cos(PI * (2 * n + 1) * k / (2.0 * HASH_GRID))
            }
        }

    /** Bilinear resample of [rect] of the luma grid to [HASH_GRID]². */
    private fun resample(
        luma: ByteArray,
        rect: Rect,
    ): DoubleArray {
        val n = CoverInspection.GRID
        val out = DoubleArray(HASH_GRID * HASH_GRID)
        for (j in 0 until HASH_GRID) {
            val sy = ((rect.y + (j + 0.5) / HASH_GRID * rect.height) * n - 0.5).coerceIn(0.0, n - 1.0)
            val y0 = floor(sy).toInt()
            val y1 = min(y0 + 1, n - 1)
            val fy = sy - y0
            for (i in 0 until HASH_GRID) {
                val sx = ((rect.x + (i + 0.5) / HASH_GRID * rect.width) * n - 0.5).coerceIn(0.0, n - 1.0)
                val x0 = floor(sx).toInt()
                val x1 = min(x0 + 1, n - 1)
                val fx = sx - x0

                fun at(
                    x: Int,
                    y: Int,
                ) = (luma[y * n + x].toInt() and 0xFF).toDouble()
                val top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx
                val bottom = at(x0, y1) * (1 - fx) + at(x1, y1) * fx
                out[j * HASH_GRID + i] = top * (1 - fy) + bottom * fy
            }
        }
        return out
    }
}
