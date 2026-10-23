package dev.saltartist.artwork

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkSafetyTest {
    @Test fun cpuFramePlaybackIsAvailable() {
        assertTrue(nativeMotionEnabled())
    }

    @Test fun paletteReadsOnlyASubsampledImage() {
        val bytes = ByteArrayOutputStream().apply {
            ImageIO.write(BufferedImage(1800, 1200, BufferedImage.TYPE_INT_RGB), "png", this)
        }.toByteArray()
        val thumbnail = checkNotNull(paletteThumbnail(bytes))
        assertTrue(thumbnail.width <= 256 && thumbnail.height <= 256)
        assertFalse(acceptableImageDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(acceptableImageDimensions(10000, 10000))
        assertTrue(acceptableImageDimensions(6000, 4000))
    }

    @Test fun evictionDropsCacheOwnershipButKeepsAConsumersReferenceValid() {
        val cache = BoundedArtworkCache<String, Any>(2)
        val held = Any()
        cache["a"] = held
        cache["b"] = Any()
        assertTrue(cache["a"] === held)
        cache["c"] = Any()
        assertEquals(null, cache["b"])
        assertTrue(cache["a"] === held)
        cache["d"] = Any()
        cache["e"] = Any()
        assertEquals(null, cache["a"])
        assertEquals(2, cache.keys.size)
        assertTrue(held !== cache["d"])
    }
}
