package dev.saltartist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saltartist.settings.DirectoryBackground
import dev.saltartist.settings.DirectoryBackgroundMode
import dev.saltartist.settings.parseBackgroundColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun DirectoryBackgroundPanel(deps: ArtistScreenDeps, foreground: Color, surface: Color, onClose: () -> Unit) {
    val background = deps.settings.read().directoryBackground
    var colorText by remember(background.color) { mutableStateOf(background.color) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(value: DirectoryBackground) {
        message = if (deps.settings.setDirectoryBackground(value)) "" else "保存失败，请检查插件配置是否可写。"
    }
    DirectoryPanel("艺术家列表背景", foreground, surface, onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DirectoryBackgroundMode.entries.forEach { mode ->
                DirectoryAction(mode.label, foreground, { save(background.copy(mode = mode)) }, selected = mode == background.mode)
            }
        }
        if (background.mode == DirectoryBackgroundMode.CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(parseBackgroundColor(colorText) ?: surface)
                    .border(1.dp, foreground.copy(alpha = 0.2f), RoundedCornerShape(6.dp)))
                BasicTextField(colorText, { colorText = it.take(7) }, singleLine = true,
                    textStyle = TextStyle(color = foreground, fontSize = 14.sp), cursorBrush = SolidColor(foreground),
                    modifier = Modifier.weight(1f).background(foreground.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(10.dp))
                DirectoryAction("使用此颜色", foreground, {
                    if (parseBackgroundColor(colorText) == null) message = "请输入六位十六进制颜色，例如 #243448。"
                    else save(background.copy(color = colorText, imagePath = ""))
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DirectoryAction(if (loading) "正在读取图片…" else "选择背景图片", foreground, {
                    val file = pickImageFile("选择艺术家列表背景") ?: return@DirectoryAction
                    loading = true
                    scope.launch {
                        try {
                            if (withContext(Dispatchers.IO) { deps.artwork.cacheLocalLogo(file) }) {
                                save(background.copy(imagePath = file.absolutePath))
                            } else message = "无法读取图片，请选择有效的 JPG、PNG、WebP 或 BMP。"
                        } finally { loading = false }
                    }
                }, enabled = !loading)
                if (background.imagePath.isNotBlank()) DirectoryAction("移除图片", foreground, { save(background.copy(imagePath = "")) })
            }
            if (background.imagePath.isNotBlank()) SaltText(File(background.imagePath).name, color = foreground.copy(alpha = 0.65f), maxLines = 2)
        }
        if (message.isNotEmpty()) SaltText(message, color = foreground, maxLines = 3)
    }
}

@Composable
internal fun DirectoryDownloadsPanel(deps: ArtistScreenDeps, foreground: Color, surface: Color, onClose: () -> Unit) {
    val progress = deps.artwork.batchDownloads.progress
    val preparing = deps.preparingDownloads.value
    DirectoryPanel("全部艺人资料下载", foreground, surface, onClose) {
        if (preparing) SaltText("正在读取完整艺人列表…", color = foreground)
        if (progress.total > 0) {
            SaltText("${if (progress.running) "下载中" else if (progress.cancelled) "已取消" else "已完成"} · ${progress.completed}/${progress.total}", color = foreground,
                weight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(5.dp).background(foreground.copy(alpha = 0.12f), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(progress.completed.toFloat() / progress.total).fillMaxHeight().background(foreground, RoundedCornerShape(3.dp)))
            }
            if (progress.currentArtist.isNotBlank()) SaltText(progress.currentArtist, color = foreground, maxLines = 2)
            SaltText("已缓存 ${progress.available} · 未匹配或暂不可用 ${progress.unavailable + progress.retry}",
                color = foreground.copy(alpha = 0.7f), maxLines = 3)
        }
        if (progress.running || preparing) DirectoryAction(if (progress.cancelling) "正在取消…" else "取消下载", foreground,
            deps::cancelArtistDownloads, enabled = !progress.cancelling)
        else DirectoryAction(if (progress.total > 0) "继续／重新检查" else "开始全部下载", foreground,
            deps::downloadAllArtists, enabled = !preparing)
    }
}

@Composable
private fun DirectoryPanel(title: String, foreground: Color, surface: Color, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).padding(bottom = 70.dp).widthIn(max = 610.dp).fillMaxWidth().heightIn(max = 580.dp)
            .clip(RoundedCornerShape(18.dp)).background(surface).border(1.dp, foreground.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
            .clickable { }.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SaltText(title, color = foreground, size = 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                DirectoryAction("完成", foreground, onClose)
            }
            content()
        }
    }
}

@Composable
private fun DirectoryAction(label: String, foreground: Color, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(foreground.copy(alpha = if (selected) 0.18f else 0.07f))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)) {
        SaltText(label, color = foreground.copy(alpha = if (enabled) 0.95f else 0.45f), size = 12.sp)
    }
}
