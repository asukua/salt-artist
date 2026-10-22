package dev.saltartist.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 用 Canvas 画控制图标。宿主不保证加载 material-icons，自绘可以避免图标字体依赖，
 * 并让颜色、尺寸跟随当前页面的主题色。
 */
@Composable
internal fun PlayGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.06f)
            lineTo(w * 0.90f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.94f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
internal fun PauseGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        drawRect(color, topLeft = Offset(w * 0.18f, h * 0.10f), size = Size(w * 0.22f, h * 0.80f))
        drawRect(color, topLeft = Offset(w * 0.60f, h * 0.10f), size = Size(w * 0.22f, h * 0.80f))
    }
}

@Composable
internal fun ShuffleGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = h * 0.12f
        drawLine(color, Offset(w * 0.08f, h * 0.24f), Offset(w * 0.92f, h * 0.76f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.08f, h * 0.76f), Offset(w * 0.92f, h * 0.24f), stroke, StrokeCap.Round)
        val headRight = Path().apply {
            moveTo(w * 0.92f, h * 0.76f)
            lineTo(w * 0.64f, h * 0.76f)
            lineTo(w * 0.90f, h * 0.46f)
            close()
        }
        drawPath(headRight, color)
        val headLeft = Path().apply {
            moveTo(w * 0.92f, h * 0.24f)
            lineTo(w * 0.64f, h * 0.24f)
            lineTo(w * 0.90f, h * 0.54f)
            close()
        }
        drawPath(headLeft, color)
    }
}

@Composable
internal fun BackGlyph(color: Color, box: Dp = 16.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        // 苹果那支 chevron 更粗、转折更钝，这里把线宽提到 16%、起点/终点往里收一点。
        val stroke = h * 0.16f
        drawLine(color, Offset(w * 0.64f, h * 0.18f), Offset(w * 0.32f, h * 0.50f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.50f), Offset(w * 0.64f, h * 0.82f), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun ChevronRightGlyph(color: Color, box: Dp = 12.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = h * 0.12f
        drawLine(color, Offset(w * 0.34f, h * 0.16f), Offset(w * 0.64f, h * 0.50f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.64f, h * 0.50f), Offset(w * 0.34f, h * 0.84f), stroke, StrokeCap.Round)
    }
}

/** 收藏心形；[filled] 为真时实心。 */
@Composable
internal fun HeartGlyph(color: Color, box: Dp = 14.dp, filled: Boolean = false) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.90f)
            cubicTo(w * 0.06f, h * 0.60f, w * 0.06f, h * 0.20f, w * 0.30f, h * 0.16f)
            cubicTo(w * 0.42f, h * 0.14f, w * 0.48f, h * 0.24f, w * 0.50f, h * 0.30f)
            cubicTo(w * 0.52f, h * 0.24f, w * 0.58f, h * 0.14f, w * 0.70f, h * 0.16f)
            cubicTo(w * 0.94f, h * 0.20f, w * 0.94f, h * 0.60f, w * 0.50f, h * 0.90f)
            close()
        }
        drawPath(path, color, style = if (filled) Fill else Stroke(width = h * 0.10f))
    }
}

/** 分享：方框 + 右上出箭。 */
@Composable
internal fun ShareGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = h * 0.10f
        drawLine(color, Offset(w * 0.50f, h * 0.60f), Offset(w * 0.50f, h * 0.06f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.26f, h * 0.26f), Offset(w * 0.50f, h * 0.06f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.74f, h * 0.26f), Offset(w * 0.50f, h * 0.06f), stroke, StrokeCap.Round)
        val box = Path().apply {
            moveTo(w * 0.20f, h * 0.44f)
            lineTo(w * 0.20f, h * 0.94f)
            lineTo(w * 0.80f, h * 0.94f)
            lineTo(w * 0.80f, h * 0.44f)
        }
        drawPath(box, color, style = Stroke(width = stroke))
    }
}

/** 圆形对勾：对应宿主顶部那颗「已完成/已入库」按钮。 */
@Composable
internal fun CheckCircleGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = h * 0.10f
        drawCircle(color, radius = w * 0.46f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(width = stroke))
        drawLine(color, Offset(w * 0.30f, h * 0.52f), Offset(w * 0.45f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.45f, h * 0.68f), Offset(w * 0.72f, h * 0.36f), stroke, StrokeCap.Round)
    }
}

/** 放大镜。 */
@Composable
internal fun SearchGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = h * 0.10f
        drawCircle(color, radius = w * 0.32f, center = Offset(w * 0.44f, h * 0.44f), style = Stroke(width = stroke))
        drawLine(color, Offset(w * 0.67f, h * 0.67f), Offset(w * 0.90f, h * 0.90f), stroke, StrokeCap.Round)
    }
}

/** 三个点（Apple 的 more 菜单）。 */
@Composable
internal fun MoreGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        val r = h * 0.075f
        listOf(0.22f, 0.50f, 0.78f).forEach { x ->
            drawCircle(color, radius = r, center = Offset(w * x, h * 0.50f))
        }
    }
}

@Composable
internal fun CloseGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val stroke = size.width * 0.12f
        drawLine(color, Offset(size.width * 0.15f, size.height * 0.15f), Offset(size.width * 0.85f, size.height * 0.85f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.85f, size.height * 0.15f), Offset(size.width * 0.15f, size.height * 0.85f), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun InfoGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        drawCircle(color, radius = w * 0.44f, style = Stroke(w * 0.08f))
        drawCircle(color, radius = w * 0.055f, center = Offset(w * 0.5f, w * 0.29f))
        drawLine(color, Offset(w * 0.5f, w * 0.45f), Offset(w * 0.5f, w * 0.72f), w * 0.09f, StrokeCap.Round)
    }
}

@Composable
internal fun CopyGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        drawRoundRect(color, Offset(w * 0.12f, w * 0.10f), Size(w * 0.54f, w * 0.65f), CornerRadius(w * 0.08f), style = Stroke(w * 0.08f))
        drawRoundRect(color, Offset(w * 0.35f, w * 0.29f), Size(w * 0.54f, w * 0.65f), CornerRadius(w * 0.08f), style = Stroke(w * 0.08f))
    }
}

@Composable
internal fun LetterGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val stroke = w * 0.09f
        drawLine(color, Offset(w * 0.14f, w * 0.88f), Offset(w * 0.48f, w * 0.10f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.48f, w * 0.10f), Offset(w * 0.83f, w * 0.88f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.29f, w * 0.59f), Offset(w * 0.68f, w * 0.59f), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun PhotoGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        drawRoundRect(color, Offset(w * 0.07f, w * 0.12f), Size(w * 0.86f, w * 0.76f), CornerRadius(w * 0.1f), style = Stroke(w * 0.08f))
        drawCircle(color, w * 0.075f, Offset(w * 0.32f, w * 0.34f))
        val path = Path().apply { moveTo(w * 0.13f, w * 0.79f); lineTo(w * 0.44f, w * 0.51f); lineTo(w * 0.61f, w * 0.65f); lineTo(w * 0.76f, w * 0.46f); lineTo(w * 0.90f, w * 0.68f) }
        drawPath(path, color, style = Stroke(w * 0.07f))
    }
}

@Composable
internal fun RefreshGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        drawArc(color, 35f, 290f, false, Offset(w * 0.15f, w * 0.15f), Size(w * 0.70f, w * 0.70f), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
        val path = Path().apply { moveTo(w * 0.76f, w * 0.10f); lineTo(w * 0.87f, w * 0.34f); lineTo(w * 0.62f, w * 0.34f) }
        drawPath(path, color, style = Stroke(w * 0.08f, cap = StrokeCap.Round))
    }
}
/** 上一首：两个左向三角 + 左侧竖线。 */
@Composable
internal fun PrevGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        drawRect(color, topLeft = Offset(w * 0.10f, h * 0.16f), size = Size(w * 0.12f, h * 0.68f))
        val path = Path().apply {
            moveTo(w * 0.88f, h * 0.16f)
            lineTo(w * 0.30f, h * 0.50f)
            lineTo(w * 0.88f, h * 0.84f)
            close()
        }
        drawPath(path, color)
    }
}

/** 下一首：两个右向三角 + 右侧竖线。 */
@Composable
internal fun NextGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = this.size.width
        val h = this.size.height
        drawRect(color, topLeft = Offset(w * 0.78f, h * 0.16f), size = Size(w * 0.12f, h * 0.68f))
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.16f)
            lineTo(w * 0.70f, h * 0.50f)
            lineTo(w * 0.12f, h * 0.84f)
            close()
        }
        drawPath(path, color)
    }
}

/** 铅笔：编辑。斜握的笔身 + 实心笔尖，比单线更像铅笔。 */
@Composable
internal fun EditGlyph(color: Color, box: Dp = 14.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        fun x(v: Float) = w * v / 100f
        fun y(v: Float) = h * v / 100f
        // 笔身：从左下笔尖指向右上，两侧平行（100×100 网格，肉眼比例才正）。
        val body = Path().apply {
            moveTo(x(14f), y(86f))
            lineTo(x(30f), y(58f))
            lineTo(x(60f), y(28f))
            lineTo(x(76f), y(44f))
            lineTo(x(46f), y(74f))
            close()
        }
        drawPath(body, color, style = Stroke(width = h * 0.085f))
        // 笔杆尾端的分隔线，能一眼区分「铅笔」和「斜杠」。
        drawLine(
            color = color,
            start = Offset(x(58f), y(26f)),
            end = Offset(x(74f), y(42f)),
            strokeWidth = h * 0.085f,
            cap = StrokeCap.Round,
        )
    }
}
