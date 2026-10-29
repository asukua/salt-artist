package dev.saltartist.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.saltartist.bridge.HostTypography

/** Read in composition so a change to Salt Player's font strategy also updates us. */
@Composable
internal fun hostFontFamily(): FontFamily? = HostTypography.textStyleLocal?.current?.fontFamily

/**
 * 统一文本入口。宿主不一定加载 Material3，这里只依赖 foundation 的 BasicText，
 * 保证插件在只装了 compose-ui / foundation 的环境里也能渲染。
 */
@Composable
internal fun SaltText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    color: Color = Color.Unspecified,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = hostFontFamily(),
            color = color,
            fontSize = size,
            fontWeight = weight,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
            textAlign = align ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}
