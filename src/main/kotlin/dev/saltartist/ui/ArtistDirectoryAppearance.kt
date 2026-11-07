package dev.saltartist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
private fun DirectoryAction(label: String, foreground: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(foreground.copy(alpha = 0.07f))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)) {
        SaltText(label, color = foreground.copy(alpha = if (enabled) 0.95f else 0.45f), size = 12.sp)
    }
}
