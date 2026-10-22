package dev.saltartist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

/** 玻璃质感底色：半透明白 + 细描边，Windows 上没有 backdrop blur，用叠色模拟。 */
internal fun Modifier.glassSurface(alpha: Float = 0.10f, radius: Float = 999f): Modifier = this
    .clip(androidx.compose.foundation.shape.RoundedCornerShape(radius.dp))
    .background(Color.White.copy(alpha = alpha))
    .border(1.dp, Color.White.copy(alpha = 0.14f), androidx.compose.foundation.shape.RoundedCornerShape(radius.dp))

internal fun contentColorOn(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF121216) else Color(0xFFFDFDFF)

/**
 * 主操作胶囊按钮。点击区域用 clickable + hover 状态控制亮度，
 * 不使用 Material Button，避免宿主主题覆盖插件配色。
 */
@Composable
internal fun GlassPill(
    label: String,
    onClick: () -> Unit,
    accent: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val baseAlpha = if (filled) 1f else if (hovered) 0.18f else 0.11f
    val textColor = if (filled) contentColorOn(accent) else Color.White.copy(alpha = if (enabled) 0.92f else 0.45f)

    Row(
        modifier = modifier
            .height(32.dp)
            .defaultMinSize(minWidth = 96.dp)
            .clip(CircleShape)
            .background(if (filled) accent else Color.White.copy(alpha = baseAlpha))
            .border(1.dp, Color.White.copy(alpha = if (filled) 0f else 0.16f), CircleShape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(7.dp))
        }
        SaltText(label, size = 12.sp, color = textColor)
    }
}

/** 圆形图标按钮（返回、展开等）。 */
@Composable
internal fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    tint: Color = Color.White.copy(alpha = 0.92f),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (hovered) 0.42f else 0.26f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(PaddingValues(0.dp)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
