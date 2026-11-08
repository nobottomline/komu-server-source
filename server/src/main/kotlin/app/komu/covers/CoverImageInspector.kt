/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import app.komu.bridge.NativeCoverImage
import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/** Pixel size and a [CoverInspection.GRID]² luma grid of a whole picture. */
class CoverInspection(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Image has no pixels" }
        require(luma.size == GRID * GRID) { "Luma grid must be ${GRID}x$GRID" }
    }

    val aspect: Double get() = width.toDouble() / height

    companion object {
        const val GRID = 64

        /** Decompression-bomb guard: covers are never this large. */
        const val MAXIMUM_PIXELS = 50_000_000L
    }
}

/**
 * Decodes image bytes for cover verification. `null` means the bytes are not
 * a decodable image within [CoverInspection.MAXIMUM_PIXELS].
 */
fun interface CoverImageInspector {
    fun inspect(bytes: ByteArray): CoverInspection?

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * The inspector this runtime can use: ImageIO through the iOS host
         * (OpenJ9 on iOS has no AWT natives) or javax.imageio on a desktop
         * JVM. `null` when neither is available; verification then fails
         * closed and the thumbnail is kept.
         */
        val platform: CoverImageInspector? by lazy {
            val candidate =
                if (EmbeddedRuntimePlatform.isIOS()) NativeCoverImageInspector.availableOrNull() else ImageIoCoverImageInspector
            candidate.also { if (it == null) logger.warn { "No cover image inspector; full-size covers stay unverified" } }
        }
    }
}

/** iOS: ImageIO in the host app through [NativeCoverImage]. */
internal object NativeCoverImageInspector : CoverImageInspector {
    private const val ABI_VERSION = 1

    fun availableOrNull(): CoverImageInspector? =
        runCatching { NativeCoverImage.abiVersion() }
            .getOrNull()
            ?.takeIf { it == ABI_VERSION }
            ?.let { this }

    override fun inspect(bytes: ByteArray): CoverInspection? {
        val raw = NativeCoverImage.lumaGrid(bytes, CoverInspection.GRID, CoverInspection.MAXIMUM_PIXELS) ?: return null
        return decode(raw)
    }

    internal fun decode(raw: ByteArray): CoverInspection? {
        if (raw.size != 8 + CoverInspection.GRID * CoverInspection.GRID) return null
        val buffer = ByteBuffer.wrap(raw)
        val width = buffer.int
        val height = buffer.int
        if (width <= 0 || height <= 0 || width.toLong() * height > CoverInspection.MAXIMUM_PIXELS) return null
        return CoverInspection(width, height, raw.copyOfRange(8, raw.size))
    }
}

/** Desktop JVM: javax.imageio with the bundled WebP readers. */
internal object ImageIoCoverImageInspector : CoverImageInspector {
    /** Decoded side length before the luma grid is drawn; bounds memory. */
    private const val DECODE_TARGET = 256

    override fun inspect(bytes: ByteArray): CoverInspection? =
        runCatching { read(bytes) }.getOrNull()

    private fun read(bytes: ByteArray): CoverInspection? {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            input ?: return null
            val reader: ImageReader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > CoverInspection.MAXIMUM_PIXELS) return null
                val param = reader.defaultReadParam
                val step = maxOf(1, minOf(width, height) / DECODE_TARGET)
                param.setSourceSubsampling(step, step, 0, 0)
                val image = reader.read(0, param) ?: return null
                return CoverInspection(width, height, lumaGrid(image))
            } finally {
                reader.dispose()
            }
        }
    }

    private fun lumaGrid(image: BufferedImage): ByteArray {
        val grid = CoverInspection.GRID
        val gray = BufferedImage(grid, grid, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = gray.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(image, 0, 0, grid, grid, null)
        } finally {
            graphics.dispose()
        }
        val out = ByteArray(grid * grid)
        gray.raster.getDataElements(0, 0, grid, grid, out)
        return out
    }
}
