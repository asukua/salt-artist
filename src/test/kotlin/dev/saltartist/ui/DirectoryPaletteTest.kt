package dev.saltartist.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectoryPaletteTest {
    @Test
    fun dialogsAndControlsHaveReadableContrastForLightDarkAndCustomColors() {
        val backgrounds = listOf(
            Color(0xFFF3F3F3), Color(0xFF202020), Color(0xFFFFFFFF), Color(0xFF000000),
            Color(0xFF808080), Color(0xFF008B8B), Color(0xFFC34B52), Color(0xFF243448),
        )
        for (background in backgrounds) {
            val palette = directoryPalette(background, background.luminance() < 0.179f)
            assertTrue(directoryContrast(palette.foreground, background) >= 4.5f, "content on $background")
            assertTrue(directoryContrast(palette.foreground, palette.panel) >= 4.5f, "dialog on $background")
            assertTrue(directoryContrast(palette.accent, background) >= 4.5f, "accent on $background")
            assertTrue(directoryContrast(palette.accent, palette.panel) >= 4.5f, "dialog accent on $background")
            assertTrue(directoryContrast(palette.accentContent, palette.accent) >= 4.5f, "selection indicator on $background")
            for (surface in listOf(background, palette.panel)) {
                val button = palette.accent.copy(alpha = palette.buttonTint).compositeOver(surface)
                assertTrue(directoryContrast(palette.accent, button) >= 4.5f, "button on $surface")
            }
            val selected = palette.accent.copy(alpha = palette.selectionTint).compositeOver(background)
            assertTrue(directoryContrast(palette.foreground, selected) >= 4.5f, "selected label on $background")
        }
    }

    @Test
    fun floatingSurfacesFollowTheEffectiveLightOrDarkAppearance() {
        val light = directoryPalette(Color(0xFFF3F3F3), false)
        val dark = directoryPalette(Color(0xFF202020), true)
        assertEquals(Color.Black, light.foreground)
        assertEquals(Color.White, dark.foreground)
        assertTrue(light.panel.luminance() > dark.panel.luminance())
    }

    @Test
    fun imageBackgroundControlsRemainReadableOverTheBrightestMaskedPixel() {
        val palette = directoryPalette(Color(0xFF202020), dark = true, imageBackdrop = true)
        val brightest = Color(0xFF5C5C5C)
        assertTrue(directoryContrast(palette.foreground, brightest) >= 4.5f)
        assertTrue(directoryContrast(palette.accent, brightest) >= 4.5f)
        assertTrue(directoryContrast(palette.accent, palette.accent.copy(alpha = palette.buttonTint).compositeOver(brightest)) >= 4.5f)
        assertTrue(directoryContrast(palette.foreground, palette.accent.copy(alpha = palette.selectionTint).compositeOver(brightest)) >= 4.5f)
    }
}
