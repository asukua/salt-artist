package dev.saltartist.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saltartist.artwork.Artwork
import dev.saltartist.artwork.ArtworkPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The image and every blur layer share one bottom fade. The opaque surface beneath
 * is the same color as the overview, so neither a fitted image nor a scrolling blur
 * layer can leave a bright seam at the boundary.
 */
@Composable
internal fun ArtistHero(
    name: String,
    meta: String,
    palette: ArtworkPalette,
    artwork: Artwork?,
    logo: ImageBitmap?,
    scroll: State<Float>,
    immersive: Boolean,
    scrollBlur: Boolean = true,
    height: Dp,
    isFavorite: Boolean,
    enabled: Boolean,
    onInfo: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    motionContent: (@Composable (Modifier, Float) -> Unit)? = null,
) {
    val heroPx = with(LocalDensity.current) { height.toPx().coerceAtLeast(1f) }
    val progress = (scroll.value / heroPx).coerceIn(0f, 1f)
    val blurProgress = heroBlurProgress(progress, scrollBlur)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RectangleShape)
            .background(palette.backdropDeep),
    ) {
        val compact = maxWidth < 640.dp
        if (artwork != null && immersive && progress < 0.99f) {
            val imageAspect = artwork.image.width.toFloat() / artwork.image.height.coerceAtLeast(1)
            val wide = imageAspect >= 1.45f
            val fittedWidth = minOf(maxWidth, maxHeight * imageAspect)
            val fittedHeight = fittedWidth / imageAspect
            val scrollFade = 1f - ((progress - 0.65f) / 0.34f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = scrollFade
                    }
                    .drawWithContent {
                        drawContent()
                        // A broad falloff preserves the upper photograph and finishes
                        // before the controls; no translucent gray rectangle covers it.
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.00f to Color.Black,
                                0.44f to Color.Black,
                                0.62f to Color.Black.copy(alpha = 0.94f),
                                0.78f to Color.Black.copy(alpha = 0.55f),
                                0.92f to Color.Black.copy(alpha = 0.12f),
                                1.00f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                if (!wide) {
                    Image(
                        bitmap = artwork.forBlurProgress(1f),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (wide) {
                    HeroStillImage(
                        artwork = artwork,
                        blurProgress = blurProgress,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                } else {
                    // Feather relative to the actual fitted artwork, not the much
                    // wider hero. The full composition stays centered and uncropped.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = fittedWidth, height = fittedHeight)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0.000f to Color.Transparent,
                                        0.025f to Color.Black,
                                        0.975f to Color.Black,
                                        1.000f to Color.Transparent,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    ) {
                        HeroStillImage(
                            artwork = artwork,
                            blurProgress = blurProgress,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                // Motion shares the photograph's exact fade and clipping. The
                // current poster remains underneath until a video frame arrives.
                if (progress < 0.86f) motionContent?.invoke(Modifier.fillMaxSize(), blurProgress)

                // With progressive blur off, retain only the fixed lower feather.
                if (!scrollBlur) {
                    val blurStart = 0.74f
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.00f to Color.Transparent,
                                        blurStart to Color.Transparent,
                                        (blurStart + (1f - blurStart) * 0.50f) to Color.Black.copy(alpha = 0.66f),
                                        1.00f to Color.Black,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    ) {
                        Image(
                            bitmap = artwork.forBlurProgress(1f),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = if (wide) ContentScale.Crop else ContentScale.Fit,
                            alignment = if (wide) Alignment.TopCenter else Alignment.Center,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = name,
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .height(if (compact) 68.dp else 82.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                BasicText(
                    text = name,
                    modifier = Modifier.widthIn(max = 780.dp),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = if (compact) 46.sp else 62.sp,
                        lineHeight = if (compact) 50.sp else 66.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 12f),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroCircle(
                    description = if (meta.isBlank()) "艺人信息" else "艺人信息，$meta",
                    onClick = onInfo,
                ) {
                    HeroInfoGlyph()
                }
                HeroCircle(
                    description = "播放 $name",
                    size = 56.dp,
                    primary = true,
                    enabled = enabled,
                    onClick = onPlay,
                ) {
                    Box(Modifier.offset(x = 2.dp)) {
                        PlayGlyph(Color(0xFF232325), 24.dp)
                    }
                }
                HeroCircle(
                    description = if (isFavorite) "取消收藏 $name" else "收藏 $name",
                    modifier = Modifier.semantics {
                        stateDescription = if (isFavorite) "已收藏" else "未收藏"
                    },
                    onClick = onToggleFavorite,
                ) {
                    HeroStarGlyph(filled = isFavorite)
                }
            }
        }
    }
}

/** Continuous interpolation through the baked blur images, with clear type/controls above. */
@Composable
private fun HeroStillImage(artwork: Artwork, blurProgress: Float, modifier: Modifier,
                           contentScale: ContentScale, alignment: Alignment = Alignment.Center) {
    val images = listOf(artwork.image) + artwork.blurred
    val blend = heroBlurBlend(blurProgress, images.size)
    Box(modifier) {
        Image(images[blend.first], null, Modifier.fillMaxSize(), contentScale = contentScale, alignment = alignment)
        if (blend.second != blend.first) Image(images[blend.second], null,
            Modifier.fillMaxSize().graphicsLayer { alpha = blend.alpha }, contentScale = contentScale, alignment = alignment)
    }
}

@Composable
private fun HeroCircle(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    primary: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = if (primary) {
        if (hovered) Color(0xFFE5E5E7) else Color.White
    } else {
        Color.White.copy(alpha = if (hovered) 0.22f else 0.12f)
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .clip(CircleShape)
            .background(background)
            .border(0.5.dp, Color.White.copy(alpha = if (primary) 0f else 0.06f), CircleShape)
            .semantics { contentDescription = description }
            .hoverable(interaction, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun HeroInfoGlyph() {
    Canvas(Modifier.size(15.dp)) {
        val stroke = size.width * 0.12f
        drawCircle(Color.White, radius = stroke * 0.56f, center = Offset(size.width * 0.50f, size.height * 0.23f))
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.50f, size.height * 0.43f),
            end = Offset(size.width * 0.50f, size.height * 0.80f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun HeroStarGlyph(filled: Boolean) {
    Canvas(Modifier.size(17.dp)) {
        val path = Path()
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension * 0.46f
        for (index in 0 until 10) {
            val angle = -PI / 2 + index * PI / 5
            val radius = if (index % 2 == 0) outer else outer * 0.45f
            val x = center.x + cos(angle).toFloat() * radius
            val y = center.y + sin(angle).toFloat() * radius
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, Color.White, style = if (filled) Fill else Stroke(width = size.minDimension * 0.09f))
    }
}
