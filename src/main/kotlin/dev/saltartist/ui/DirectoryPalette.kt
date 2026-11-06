package dev.saltartist.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver

/** Shared colors for directory content and its opaque floating surfaces. */
internal data class DirectoryPalette(
    val background: Color,
    val foreground: Color,
    val panel: Color,
    val accent: Color,
    val accentContent: Color,
    val buttonTint: Float,
    val selectionTint: Float,
)

internal fun directoryContrast(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

internal fun directoryPalette(background: Color, dark: Boolean, imageBackdrop: Boolean = false): DirectoryPalette {
    val foreground = if (dark) Color.White else Color.Black
    val panel = if (dark) Color(0xFF303032) else Color(0xFFF9F9F9)
    val candidate = if (dark) Color(0xFFFF7585) else Color(0xFFB42243)
    // The image receives a 64% black overlay. Its brightest possible pixel is
    // approximately #5C5C5C, irrespective of the opaque fallback surface.
    val imageBrightest = Color(0xFF5C5C5C)
    val contentSurfaces = if (imageBackdrop) listOf(background, imageBrightest) else listOf(background)
    val accent = if (contentSurfaces.all { directoryContrast(candidate, it) >= 4.5f } && directoryContrast(candidate, panel) >= 4.5f)
        candidate else foreground
    val accentContent = if (directoryContrast(Color.White, accent) >= directoryContrast(Color.Black, accent)) Color.White else Color.Black
    val buttonTint = if ((contentSurfaces + panel).all {
        directoryContrast(accent, accent.copy(alpha = 0.12f).compositeOver(it)) >= 4.5f
    }) 0.12f else 0f
    val selectionTint = if (contentSurfaces.all { directoryContrast(foreground, accent.copy(alpha = 0.17f).compositeOver(it)) >= 4.5f })
        0.17f else 0f
    return DirectoryPalette(background, foreground, panel, accent, accentContent, buttonTint, selectionTint)
}
