package dev.saltartist.artwork

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteExtractorTest {
    @Test
    fun preservesColorInMediumBrightLowerBandInsteadOfClippingToGray() {
        // All source channels exceed the previous per-channel ceiling. A bright
        // magenta upper area also makes accidental whole-photo sampling visible.
        val image = solidImage(0xFFDC80DC.toInt())
        for (y in 64 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, 0xFFA0D2B9.toInt())
        }

        val palette = PaletteExtractor.extract(image)

        listOf(palette.backdrop, palette.backdropDeep).forEach { color ->
            assertTrue(color.green > color.blue && color.blue > color.red, "$color lost the lower band's green hue")
            assertTrue(color.green - color.red >= 0.02f, "$color collapsed to gray")
        }
    }

    @Test
    fun deepBackgroundIsDarkerThanBackdropForDifferentPhotoTones() {
        representativeTones.forEach { rgb ->
            val palette = PaletteExtractor.extract(solidImage(rgb))
            assertTrue(
                palette.backdropDeep.luminance() < palette.backdrop.luminance(),
                "Deep background should be darker for ${rgb.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun bodyAndSeventyPercentSecondaryTextMeetContrastOnDeepBackground() {
        representativeTones.forEach { rgb ->
            val palette = PaletteExtractor.extract(solidImage(rgb))
            val background = palette.backdropDeep
            val body = palette.onBackdrop
            val secondary = body.copy(alpha = 0.70f).compositeOver(background)

            assertTrue(contrast(body, background) >= 4.5f, "Body contrast failed for $background")
            assertTrue(contrast(secondary, background) >= 4.5f, "Secondary text contrast failed for $background")
        }
    }

    @Test
    fun fullyTransparentImageUsesFallbackPalette() {
        val transparentImage = BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB)
        assertEquals(ArtworkPalette.Fallback, PaletteExtractor.extract(transparentImage))
    }

    private fun solidImage(rgb: Int): BufferedImage =
        BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) setRGB(x, y, rgb)
            }
        }

    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.luminance()
        val second = background.luminance()
        return (max(first, second) + 0.05f) / (min(first, second) + 0.05f)
    }

    private val representativeTones = listOf(
        0xFFA0D2B9.toInt(), // Green midtones from the reproduced gray-clipping case.
        0xFFDCAE9F.toInt(), // Warm photograph.
        0xFFAAB9DC.toInt(), // Cool photograph.
        0xFFBBBBBB.toInt(), // Gray lower edge.
        0xFFFFFFFF.toInt(), // Brightest lower edge exercises the contrast limit.
    )
}
