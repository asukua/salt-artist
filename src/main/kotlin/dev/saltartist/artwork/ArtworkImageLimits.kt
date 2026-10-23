package dev.saltartist.artwork

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.ceil

internal const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
internal fun acceptableImageDimensions(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width.toLong() * height <= 36_000_000L

/** Palette extraction never expands a full resolution image into the Java heap. */
internal fun paletteThumbnail(bytes: ByteArray): BufferedImage? {
    require(bytes.size <= MAX_IMAGE_BYTES)
    return ImageIO.createImageInputStream(bytes.inputStream())?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(acceptableImageDimensions(width, height))
            val step = ceil(maxOf(width, height) / 256.0).toInt().coerceAtLeast(1)
            val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
            reader.read(0, param)
        } finally { reader.dispose() }
    }
}
