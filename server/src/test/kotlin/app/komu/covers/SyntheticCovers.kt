package app.komu.covers

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Generated test pictures. Real covers are never committed; a seeded
 * composition of shapes and gradients stands in for cover art.
 */
object SyntheticCovers {
    fun art(
        seed: Int,
        width: Int = 800,
        height: Int = 1200,
    ): BufferedImage {
        val random = Random(seed)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = java.awt.GradientPaint(0f, 0f, Color(random.nextInt()), width.toFloat(), height.toFloat(), Color(random.nextInt()))
        g.fillRect(0, 0, width, height)
        repeat(14) {
            g.color = Color(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val w = (width * (0.1 + random.nextDouble() * 0.5)).toInt()
            val h = (height * (0.1 + random.nextDouble() * 0.5)).toInt()
            val x = random.nextInt(width - w / 2)
            val y = random.nextInt(height - h / 2)
            if (random.nextBoolean()) g.fillOval(x, y, w, h) else g.fillRect(x, y, w, h)
        }
        g.dispose()
        return image
    }

    fun scaled(
        source: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    /** WordPress-style hard crop: centre crop to the box aspect, then scale. */
    fun cropped(
        source: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val target = width.toDouble() / height
        val sourceAspect = source.width.toDouble() / source.height
        val (cw, ch) =
            if (sourceAspect > target) (source.height * target).toInt() to source.height else source.width to (source.width / target).toInt()
        val sub = source.getSubimage((source.width - cw) / 2, (source.height - ch) / 2, cw, ch)
        return scaled(sub, width, height)
    }

    fun png(image: BufferedImage): ByteArray = encode(image, "png")

    fun jpeg(image: BufferedImage): ByteArray = encode(image, "jpg")

    private fun encode(
        image: BufferedImage,
        format: String,
    ): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()

    fun inspect(bytes: ByteArray): CoverInspection = ImageIoCoverImageInspector.inspect(bytes)!!
}
