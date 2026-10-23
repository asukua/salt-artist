package dev.saltartist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saltartist.am.ArtistMatchCandidate
import dev.saltartist.artwork.ArtworkPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/** Search results are suggestions; only an explicit selection writes an artist binding. */
@Composable
internal fun ArtistMatcher(
    artistName: String,
    palette: ArtworkPalette,
    deps: ArtistScreenDeps,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val options = deps.settings.read()
    val binding = deps.settings.artistBinding(artistName)
    var query by remember(artistName) { mutableStateOf(artistName) }
    var requestedQuery by remember(artistName) { mutableStateOf(artistName) }
    var request by remember(artistName) { mutableStateOf(0) }
    var results by remember(artistName) { mutableStateOf(emptyList<ArtistMatchCandidate>()) }
    var loading by remember(artistName) { mutableStateOf(true) }
    var message by remember(artistName) { mutableStateOf<String?>(null) }
    LaunchedEffect(artistName, requestedQuery, request, options.identityPolicy.cacheToken) {
        loading = true
        message = null
        results = emptyList()
        try {
            results = withContext(Dispatchers.IO) {
                deps.artwork.searchArtistCandidates(requestedQuery, options.identityPolicy)
            }
            if (results.isEmpty()) message = "没有找到可用候选。试试其他名字，或粘贴 Apple Music 艺人链接 / ID。"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "搜索暂时不可用，请稍后重试或调整配置中的地区顺序。"
        } finally {
            loading = false
        }
    }
    Box(
        Modifier.fillMaxSize().padding(bottom = 76.dp).background(Color.Black.copy(alpha = 0.56f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 610.dp).fillMaxWidth().heightIn(max = 530.dp)
                .clip(RoundedCornerShape(20.dp)).background(palette.backdropDeep)
                .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                .clickable { }.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SaltText("匹配 Apple Music 艺人", size = 20.sp, weight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.weight(1f))
                MatcherAction("完成", onClose)
            }
            SaltText("为「$artistName」选择素材来源，不修改曲库艺人名或歌曲。", size = 12.sp,
                color = Color.White.copy(alpha = 0.65f), maxLines = 3, lineHeight = 19.sp)
            if (binding != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SaltText("已绑定 ID $binding", size = 12.sp, color = Color.White.copy(alpha = 0.68f),
                        modifier = Modifier.weight(1f))
                    MatcherAction("恢复自动匹配", {
                        if (deps.settings.setArtistBinding(artistName, null)) onChanged()
                        else message = "保存失败，请检查插件配置是否可写。"
                    })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(2048) },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.07f)).padding(12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) SaltText("艺人名、艺人链接或 ID", color = Color.White.copy(alpha = 0.4f))
                            inner()
                        }
                    },
                )
                MatcherAction("搜索", {
                    requestedQuery = query.trim()
                    request++
                }, enabled = !loading && query.isNotBlank(), primary = true)
            }
            if (!options.recognizeAliases) SaltText("选择候选后将启用艺人别名识别。", size = 12.sp,
                color = Color.White.copy(alpha = 0.58f), maxLines = 2)
            if (loading) SaltText("正在搜索…", size = 13.sp, color = Color.White.copy(alpha = 0.65f))
            message?.let { SaltText(it, size = 12.sp, color = Color.White.copy(alpha = 0.68f), maxLines = 4, lineHeight = 19.sp) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { candidate ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.045f)).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SaltText(candidate.name, size = 15.sp, weight = FontWeight.SemiBold, color = Color.White, maxLines = 2)
                            SaltText("Apple Music · ID ${candidate.id}", size = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        }
                        MatcherAction("查看", { runCatching { Desktop.getDesktop().browse(URI(candidate.url)) } })
                        MatcherAction(if (options.recognizeAliases) "匹配" else "匹配并启用", {
                            if (deps.settings.setArtistBinding(artistName, candidate.id)) onChanged()
                            else message = "保存失败，请检查插件配置是否可写。"
                        }, primary = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatcherAction(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    Box(Modifier.height(33.dp).clip(RoundedCornerShape(9.dp))
        .background(if (primary) Color.White.copy(alpha = if (enabled) 1f else 0.18f) else Color.Transparent)
        .clickable(role = Role.Button, enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) {
        SaltText(label, size = 12.sp, weight = FontWeight.Medium,
            color = if (primary) Color(0xFF222225) else Color.White.copy(alpha = 0.75f))
    }
}
