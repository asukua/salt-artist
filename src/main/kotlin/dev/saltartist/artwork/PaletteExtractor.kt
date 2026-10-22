package dev.saltartist.artwork

import androidx.compose.ui.graphics.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 从封面图提取的配色。艺人页的整块背景、渐隐遮罩、玻璃按钮着色都由它驱动，
 * 保证「换艺人即换氛围」，而不是固定一套商品化的深色皮肤。
 */
data class ArtworkPalette(
    /** 页面背景基色，取头图可见下缘并轻度去饱和，用来承接全幅写真。 */
    val backdrop: Color,
    /** 背景纵向渐变的落点，比 [backdrop] 更暗。 */
    val backdropDeep: Color,
    /** 强调色：播放按钮、进度条、当前曲目高亮。 */
    val accent: Color,
    /** 标题文字色，保证与 backdrop 对比度足够。 */
    val onBackdrop: Color,
    /**
     * 写真下半部分（大标题所在区域）是不是亮底。
     * 亮底写真必须提前压深，否则白字会糊在浅色天空/白背景里（347aidan 那张就是）。
     */
    val heroLight: Boolean = false,
) {
    companion object {
        val Fallback = ArtworkPalette(
            backdrop = Color(0xFF29292E),
            backdropDeep = Color(0xFF19191E),
            accent = Color(0xFFFA5C6B),
            onBackdrop = Color(0xFFF7F7FA),
            heroLight = false,
        )
    }
}

internal object PaletteExtractor {
    fun extract(image: BufferedImage): ArtworkPalette {
        val small = downscale(image, 96)
        val buckets = HashMap<Int, Bucket>()

        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val rgb = small.getRGB(x, y)
                val a = (rgb ushr 24) and 0xFF
                if (a < 32) continue
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val hsv = rgbToHsv(r, g, b)
                // 纯白、纯黑、灰阶对氛围没有贡献，直接跳过。
                if (hsv.s < 0.14f || hsv.v < 0.10f || hsv.v > 0.98f) continue
                val key = (r / 24 shl 16) or (g / 24 shl 8) or (b / 24)
                val weight = hsv.s * hsv.s * 1.8f + 0.04f
                val bucket = buckets.getOrPut(key) { Bucket() }
                bucket.r += r * weight
                bucket.g += g * weight
                bucket.b += b * weight
                bucket.weight += weight
            }
        }

        val best = buckets.values.maxByOrNull { it.weight }
        val dominant = best?.let {
            rgbToHsv(
                (it.r / it.weight).toInt().coerceIn(0, 255),
                (it.g / it.weight).toInt().coerceIn(0, 255),
                (it.b / it.weight).toInt().coerceIn(0, 255),
            )
        }

        // 页面底色从头图下缘取样，避免整张照片中面积很小的高饱和颜色
        // 被扩展成满屏蓝/青底。适度去饱和后统一缩放亮度，保留下缘的色相。
        val lowerTone = lowerBandTone(small) ?: return ArtworkPalette.Fallback
        val backdrop = shade(lowerTone, saturation = 0.64f, brightness = 0.68f, maxChannel = 0x58)
        val backdropDeep = shade(lowerTone, saturation = 0.66f, brightness = 0.50f, maxChannel = 0x3C)
        val accent = dominant?.let {
            hsvToColor(it.h, min(1f, max(0.55f, it.s * 1.05f)), 0.96f)
        } ?: ArtworkPalette.Fallback.accent
        val light = bandIsLight(small)
        // 页面背景保持深色，标题使用浅色；亮图的标题区域由单独的底部渐变保证对比度。
        val onBackdrop = Color(0xFFF7F7FA)

        return ArtworkPalette(backdrop, backdropDeep, accent, onBackdrop, heroLight = light)
    }

    /**
     * 判断写真下半部分（大标题压着的那块）是否偏亮。
     * 只看下半部分，因为标题在底部；上半部分再亮也不影响文字可读性。
     */
    private fun lowerBandTone(small: BufferedImage): IntArray? {
        val aspect = small.width.toFloat() / small.height.coerceAtLeast(1)
        // Wide artwork is center-cropped vertically into the desktop hero. Sample the
        // bottom of that visible crop rather than the source's hidden bottom rows.
        val visibleHeight = if (aspect >= 1.45f) (aspect / 2.25f).coerceAtMost(1f) else 1f
        val fromY = (small.height * visibleHeight * 0.82f)
            .toInt().coerceIn(0, small.height - 1)
        val toY = (small.height * visibleHeight * 0.98f)
            .toInt().coerceIn(fromY + 1, small.height)
        val fromX = (small.width * 0.08f).toInt()
        val toX = (small.width * 0.92f).toInt().coerceAtLeast(fromX + 1)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var count = 0
        for (y in fromY until toY) {
            for (x in fromX until toX) {
                val rgb = small.getRGB(x, y)
                if (((rgb ushr 24) and 0xFF) < 32) continue
                r += (rgb shr 16) and 0xFF
                g += (rgb shr 8) and 0xFF
                b += rgb and 0xFF
                count++
            }
        }
        if (count == 0) return null
        return intArrayOf((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun shade(rgb: IntArray, saturation: Float, brightness: Float, maxChannel: Int): Color {
        val luma = luminance(rgb[0], rgb[1], rgb[2]).toFloat()
        val channels = rgb.map { luma + (it - luma) * saturation }
        // 逐通道截顶会把中亮图片的三个通道都裁成同一个值，最终整页变灰。
        val scale = min(brightness, maxChannel / channels.max().coerceAtLeast(1f))
        val (r, g, b) = channels.map { (it * scale).toInt().coerceIn(0, 255) }
        return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)
    }

    private fun bandIsLight(small: BufferedImage): Boolean {
        val fromY = (small.height * 0.52f).toInt().coerceAtMost(small.height - 1)
        var sum = 0.0
        var count = 0
        for (y in fromY until small.height) {
            for (x in 0 until small.width) {
                val rgb = small.getRGB(x, y)
                val a = (rgb ushr 24) and 0xFF
                if (a < 32) continue
                sum += luminance((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                count++
            }
        }
        if (count == 0) return false
        return (sum / count) > 163.0   // ≈0.64*255：比中性灰明显偏亮才判定为亮底
    }

    private fun downscale(image: BufferedImage, target: Int): BufferedImage {
        val scale = min(target.toFloat() / image.width, target.toFloat() / image.height).coerceAtMost(1f)
        val w = max(1, (image.width * scale).toInt())
        val h = max(1, (image.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(image, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun luminance(r: Int, g: Int, b: Int): Int = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

    private class Bucket {
        var r = 0f
        var g = 0f
        var b = 0f
        var weight = 0f
    }

    private data class Hsv(val h: Float, val s: Float, val v: Float)

    private fun rgbToHsv(r: Int, g: Int, b: Int): Hsv {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC
        val h = when {
            delta < 1e-5f -> 0f
            maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
            maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        val s = if (maxC <= 0f) 0f else delta / maxC
        return Hsv(if (h < 0) h + 360f else h, s, maxC)
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Color {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }
}
