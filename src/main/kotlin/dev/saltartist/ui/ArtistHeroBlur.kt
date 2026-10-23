package dev.saltartist.ui

internal fun heroBlurProgress(scrollProgress: Float, enabled: Boolean): Float {
    if (!enabled) return 0f
    val p = ((scrollProgress - 0.015f) / 0.58f).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

internal data class HeroBlurBlend(val first: Int, val second: Int, val alpha: Float)
internal fun heroBlurBlend(progress: Float, imageCount: Int): HeroBlurBlend {
    val last = (imageCount - 1).coerceAtLeast(0)
    val position = progress.coerceIn(0f, 1f) * last
    val first = position.toInt().coerceIn(0, last)
    return HeroBlurBlend(first, (first + 1).coerceAtMost(last), position - first)
}
