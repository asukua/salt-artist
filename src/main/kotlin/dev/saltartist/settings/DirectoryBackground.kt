package dev.saltartist.settings

import androidx.compose.ui.graphics.Color

enum class DirectoryBackgroundMode(val label: String) {
    SYSTEM("跟随椒盐设置"), CUSTOM("自定义")
}

internal fun parseBackgroundColor(value: String): Color? = value.trim().removePrefix("#")
    .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)?.let { Color(0xFF000000L or it) }

data class DirectoryBackground(
    val mode: DirectoryBackgroundMode = DirectoryBackgroundMode.SYSTEM,
    val color: String = "#243448",
    val imagePath: String = "",
)
