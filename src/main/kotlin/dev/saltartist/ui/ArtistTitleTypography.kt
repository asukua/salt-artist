package dev.saltartist.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import org.jetbrains.skia.FontMgr

/** Hero names without an Apple Music or user-supplied logo. */
@OptIn(ExperimentalTextApi::class)
internal object ArtistTitleTypography {
    val fontFamily: FontFamily by lazy {
        // Use SF Pro only when the host already has it; never bundle Apple fonts.
        val appleFamily = runCatching {
            listOf("SF Pro Display", "SF Pro").firstOrNull { family ->
                FontMgr.default.matchFamily(family).use { it.count() > 0 }
            }?.let { FontFamily(it) }
        }.getOrNull()
        if (appleFamily != null) return@lazy appleFamily
        // Load through the plugin's class loader; the host context loader may not
        // see plugin resources. The OS supplies fallback glyphs for other scripts.
        runCatching {
            val bytes = checkNotNull(ArtistTitleTypography::class.java.classLoader
                .getResourceAsStream("fonts/InterDisplay-Bold.ttf")).use { it.readBytes() }
            FontFamily(Font("Inter Display Bold", bytes, FontWeight.Bold))
        }.getOrElse { FontFamily.SansSerif }
    }
}
