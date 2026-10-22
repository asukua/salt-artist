package dev.saltartist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saltartist.am.AmArtistDetails
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.data.ArtistPageModel

/** Artist facts are remote metadata only; missing fields do not get library substitutes. */
@Composable
internal fun ArtistInfo(
    model: ArtistPageModel,
    palette: ArtworkPalette,
    portrait: ImageBitmap?,
    details: AmArtistDetails?,
    onClose: () -> Unit,
) {
    if (showLibraryArtistInfo(details)) {
        LibraryArtistInfo(model, palette, onClose)
        return
    }
    val name = model.name
    val scrollState = rememberLazyListState()
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        val panelHeight = (maxHeight - 132.dp).coerceIn(240.dp, 690.dp)
        val background = palette.backdropDeep
        Box(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 80.dp)
                .widthIn(max = 570.dp).fillMaxWidth().height(if (portrait == null && details == null) 190.dp else panelHeight)
                .clip(RoundedCornerShape(22.dp)).background(background)
                .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                .clickable { },
        ) {
            LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
                item {
                    Box(Modifier.fillMaxWidth().height(if (portrait != null) 340.dp else 155.dp)) {
                        portrait?.let {
                            Image(it, "$name 的艺人照片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            0f to Color.Transparent, 0.40f to Color.Transparent,
                            0.76f to background.copy(alpha = 0.68f), 1f to background,
                        )))
                        SaltText(name, Modifier.align(Alignment.BottomStart).padding(horizontal = 28.dp, vertical = 20.dp),
                            size = 31.sp, weight = FontWeight.Bold, color = Color.White, maxLines = 2)
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 30.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        details?.born?.takeIf(String::isNotBlank)?.let { ArtistFact("出生", it) }
                        details?.formed?.takeIf(String::isNotBlank)?.let { ArtistFact("成立", it) }
                        details?.origin?.takeIf(String::isNotBlank)?.let { ArtistFact("来自", it) }
                        if (!details?.genres.isNullOrEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SaltText("流派", color = Color.White.copy(alpha = 0.56f), size = 13.sp)
                                // Text wraps naturally even when the service supplies many genres.
                                SaltText(details.genres.joinToString(" · "), size = 14.sp, color = Color.White, maxLines = 5, lineHeight = 21.sp)
                            }
                        }
                        details?.biography?.takeIf(String::isNotBlank)?.let {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SaltText("关于 $name", size = 20.sp, weight = FontWeight.Bold, color = Color.White, maxLines = 2)
                                SaltText(it, size = 14.sp, lineHeight = 23.sp, color = Color.White.copy(alpha = 0.88f), maxLines = Int.MAX_VALUE)
                            }
                        }
                        if (details != null || portrait != null) {
                            SaltText("资料来自 Apple Music", size = 11.sp, color = Color.White.copy(alpha = 0.40f))
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 12.dp, horizontal = 5.dp),
                style = ScrollbarStyle(26.dp, 4.dp, RoundedCornerShape(2.dp), 150,
                    Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.5f)),
            )
            PanelCloseButton(onClose, Modifier.align(Alignment.TopStart).padding(14.dp))
        }
    }
}

/** Keep local collection facts separate from the remote artist biography. */
@Composable
private fun LibraryArtistInfo(model: ArtistPageModel, palette: ArtworkPalette, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 28.dp).padding(bottom = 70.dp).widthIn(max = 440.dp).fillMaxWidth()
                .clip(RoundedCornerShape(18.dp)).background(palette.backdropDeep)
                .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                .clickable { }.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SaltText(model.name, size = 24.sp, weight = FontWeight.Bold, color = Color.White, maxLines = 2)
            SaltText("${model.trackCount} 首歌曲 · ${model.albumCount} 张专辑", size = 13.sp, color = Color.White.copy(alpha = 0.72f))
            model.yearRange?.let { years ->
                SaltText(if (years.first == years.last) "${years.first}" else "${years.first}–${years.last}", color = Color.White.copy(alpha = 0.72f))
            }
            if (model.genres.isNotEmpty()) SaltText(model.genres.joinToString(" · "), color = Color.White.copy(alpha = 0.72f), maxLines = 3)
            SaltText("来自你的音乐资料库", size = 11.sp, color = Color.White.copy(alpha = 0.50f))
            SaltText("完成", size = 13.sp, weight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.align(Alignment.End).clickable(onClick = onClose).padding(8.dp))
        }
    }
}

@Composable
private fun ArtistFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SaltText(label, size = 13.sp, color = Color.White.copy(alpha = 0.56f))
        SaltText(value, size = 15.sp, color = Color.White, maxLines = 4, lineHeight = 22.sp)
    }
}

@Composable
internal fun ArtistLogoEditor(
    name: String,
    logo: ImageBitmap?,
    isCustom: Boolean,
    palette: ArtworkPalette,
    onChoose: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 80.dp).widthIn(max = 460.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(palette.backdropDeep)
                .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp)).clickable { }.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SaltText("自定义艺术字", size = 21.sp, weight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                PanelCloseButton(onClose)
            }
            Box(Modifier.fillMaxWidth().height(122.dp).clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.045f)).padding(22.dp), contentAlignment = Alignment.Center) {
                if (logo != null) Image(logo, "当前艺术字预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else SaltText(name, size = 29.sp, weight = FontWeight.Bold, color = Color.White, maxLines = 2)
            }
            SaltText("导入透明背景的 PNG 或 WebP 图片，作为艺人页中央的艺术字。", size = 13.sp,
                color = Color.White.copy(alpha = 0.67f), maxLines = 3, lineHeight = 21.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelAction("选择图片", onChoose, Modifier.weight(1f), primary = true)
                PanelAction("恢复默认", onReset, Modifier.weight(1f), enabled = isCustom)
            }
        }
    }
}

@Composable
private fun PanelAction(label: String, onClick: () -> Unit, modifier: Modifier, primary: Boolean = false, enabled: Boolean = true) {
    Box(modifier.height(37.dp).clip(RoundedCornerShape(9.dp))
        .background(if (primary) Color.White else Color.White.copy(alpha = if (enabled) 0.10f else 0.035f))
        .clickable(role = Role.Button, enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        SaltText(label, size = 13.sp, weight = FontWeight.Medium,
            color = if (primary) Color(0xFF222225) else Color.White.copy(alpha = if (enabled) 0.9f else 0.3f))
    }
}

@Composable
private fun PanelCloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.27f))
        .semantics { contentDescription = "关闭" }.clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
        CloseGlyph(Color.White, 14.dp)
    }
}
