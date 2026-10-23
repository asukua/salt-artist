package dev.saltartist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.saltartist.am.artistIdentityKey
import dev.saltartist.bridge.*
import dev.saltartist.settings.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val directoryBackground = Color(0xFF202020)
private val directoryMuted = Color(0xFFAAAAAE)
private val directoryAccent = Color(0xFFFF586F)

/** Sibling overlay in the navigation Box, below the host's mini player. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistDirectoryScreen(deps: ArtistScreenDeps) {
    val source = deps.directory.artists ?: return // Keep the native loading/error screen.
    var nativeManagement by remember { mutableStateOf(false) }
    if (nativeManagement) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = 8.dp)) { DirectoryButton("返回艺人视图") { nativeManagement = false } }
        }
        return
    }
    val settings = deps.settings.read()
    val prefs = settings.directory
    val tracks = deps.repository.tracks.orEmpty()
    val stats = remember(tracks) { directoryStatistics(tracks) }
    val favorites = deps.settings.favorites()
    val navigator = deps.navigation.navigator()
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val visible = remember(source, prefs, stats, favorites, settings.favoritesFirst, query) {
        orderDirectoryArtists(source, prefs, stats, favorites, settings.favoritesFirst, query)
    }
    fun save(transform: (ArtistDirectoryPreferences) -> ArtistDirectoryPreferences): Boolean {
        val ok = deps.settings.updateDirectory(transform)
        message = if (ok) "" else "设置保存失败，请重试"
        return ok
    }
    fun activate(artist: DirectoryArtist) {
        if (selecting) selected = if (artist.name in selected) selected - artist.name else selected + artist.name
        else if (!deps.navigation.openArtist(navigator, artist.name)) message = "无法打开艺人页面"
    }

    Box(Modifier.fillMaxSize().background(directoryBackground).clickable(indication = null,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SaltText("艺术家", size = 28.sp, weight = FontWeight.Bold, color = Color.White)
                SaltText(source.count { !prefs.isHidden(it.name) }.toString(), size = 15.sp, color = directoryMuted)
                Spacer(Modifier.weight(1f))
                DirectoryButton("原生管理") { nativeManagement = true }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectoryChoiceMenu("视图 · ${prefs.view.label}", ArtistDirectoryView.entries, prefs.view, { it.label }) {
                    save { p -> p.copy(view = it) }
                }
                DirectoryChoiceMenu("大小 · ${prefs.size.label}", ArtistDirectorySize.entries, prefs.size, { it.label }) {
                    save { p -> p.copy(size = it) }
                }
                DirectoryChoiceMenu("排序 · ${prefs.sort.label}", ArtistDirectorySort.entries, prefs.sort, { it.label }) {
                    save { p -> p.copy(sort = it) }
                }
                DirectoryButton(if (selecting) "取消选择" else "删除艺人") { selecting = !selecting; selected = emptySet() }
                if (selecting) {
                    DirectoryButton("全选结果") { selected = visible.mapTo(linkedSetOf()) { it.name } }
                    DirectoryButton("移除 (${selected.size})", enabled = selected.isNotEmpty(), accent = true) { confirming = true }
                }
                if (prefs.hidden.isNotEmpty()) DirectoryButton("已移除 (${prefs.hidden.size})") { restoring = true }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.widthIn(max = 320.dp).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SearchGlyph(directoryMuted)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(directoryAccent),
                        decorationBox = { field -> Box { if (query.isEmpty()) SaltText("在艺人中搜索", size = 14.sp, color = directoryMuted); field() } })
                    if (query.isNotEmpty()) SaltText("×", Modifier.padding(start = 8.dp).clickable { query = "" }, size = 20.sp, color = directoryMuted)
                }
                Spacer(Modifier.width(14.dp))
                if (query.isNotBlank()) SaltText("${visible.size} 个结果", size = 13.sp, color = directoryMuted)
                if (selecting) SaltText("已选择 ${selected.size} 位艺人", Modifier.padding(start = 12.dp), size = 13.sp, color = directoryMuted)
            }
            if (message.isNotEmpty()) SaltText(message, Modifier.padding(top = 8.dp), size = 13.sp, color = directoryAccent)
            Spacer(Modifier.height(12.dp))
            if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SaltText(if (source.isEmpty()) "曲库中还没有艺人" else "没有符合条件的艺人", color = directoryMuted)
            } else if (prefs.view == ArtistDirectoryView.GRID || prefs.view == ArtistDirectoryView.PORTRAITS) {
                val grid = rememberLazyGridState()
                LaunchedEffect(query, prefs.view, prefs.size, prefs.sort) { grid.scrollToItem(0) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(GridCells.Adaptive((156 * prefs.size.scale).dp), state = grid,
                        modifier = Modifier.fillMaxSize().padding(end = 10.dp), contentPadding = PaddingValues(bottom = 112.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visible, key = { it.name }) { artist ->
                            DirectoryCard(artist, stats[artistIdentityKey(artist.name)] ?: DirectoryStats(), prefs,
                                selecting, artist.name in selected, artist.favorite || deps.settings.isFavorite(artist.name), deps) { activate(artist) }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            } else {
                val list = rememberLazyListState()
                LaunchedEffect(query, prefs.view, prefs.size, prefs.sort) { list.scrollToItem(0) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    SaltText("艺人", Modifier.weight(1f), size = 12.sp, color = directoryMuted)
                    SaltText("歌曲 / 专辑", Modifier.width(110.dp), size = 12.sp, color = directoryMuted)
                    SaltText("最新添加", Modifier.width(110.dp), size = 12.sp, color = directoryMuted)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 10.dp), contentPadding = PaddingValues(bottom = 112.dp)) {
                        items(visible, key = { it.name }) { artist ->
                            DirectoryRow(artist, stats[artistIdentityKey(artist.name)] ?: DirectoryStats(), prefs,
                                selecting, artist.name in selected, artist.favorite || deps.settings.isFavorite(artist.name), deps) { activate(artist) }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
        if (confirming) DirectoryDialog("移除 ${selected.size} 位艺人？", { confirming = false }) {
            SaltText("仅从艺人列表移除，可在「已移除」中恢复。歌曲和磁盘文件会保留。", color = directoryMuted, size = 14.sp, maxLines = 3)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DirectoryButton("取消") { confirming = false }
                DirectoryButton("确认移除", accent = true) {
                    if (save { it.copy(hidden = it.hidden + selected) }) {
                        confirming = false; selecting = false; selected = emptySet()
                    }
                }
            }
        }
        if (restoring) DirectoryDialog("已移除的艺人", { restoring = false }) {
            if (prefs.hidden.isEmpty()) SaltText("没有已移除的艺人", color = directoryMuted)
            else {
                DirectoryButton("全部恢复") { save { it.copy(hidden = emptySet()) } }
                LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth().padding(top = 12.dp)) {
                    items(prefs.hidden.toList().sorted(), key = { it }) { name ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            SaltText(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                            DirectoryButton("恢复") { save { p -> p.copy(hidden = p.hidden - name) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryPortrait(artist: DirectoryArtist, stats: DirectoryStats, deps: ArtistScreenDeps, modifier: Modifier) {
    val portrait = deps.artwork.artistAvatar(artist.name, deps.settings.read().identityPolicy)
        ?: deps.artwork.directoryCover(artist.coverPath)
        ?: stats.coverId.takeIf(String::isNotBlank)?.let { deps.artwork.cover(it)?.image }
    Box(modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
        if (portrait != null) Image(portrait, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else SaltText(artist.name.take(1), size = 24.sp, color = directoryMuted)
    }
}

@Composable
private fun DirectoryCard(artist: DirectoryArtist, stats: DirectoryStats, prefs: ArtistDirectoryPreferences,
                          selecting: Boolean, selected: Boolean, favorite: Boolean, deps: ArtistScreenDeps, onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (selected) directoryAccent.copy(alpha = 0.17f) else Color.Transparent)
        .semantics { contentDescription = artist.name }.clickable(role = Role.Button, onClick = onClick).padding(8.dp)) {
        val diameter = minOf(maxWidth, (148 * prefs.size.scale).dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DirectoryPortrait(artist, stats, deps, Modifier.size(diameter))
            if (prefs.view != ArtistDirectoryView.PORTRAITS) {
                SaltText((if (favorite) "★ " else "") + artist.name, Modifier.fillMaxWidth().padding(top = 10.dp),
                    size = 15.sp, weight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                if (stats.tracks > 0) SaltText("${stats.tracks} 首歌曲 · ${stats.albums} 张专辑", Modifier.fillMaxWidth().padding(top = 3.dp),
                    size = 12.sp, color = directoryMuted, maxLines = 1)
            }
        }
        if (selecting) SaltText(if (selected) "✓" else "○", Modifier.align(Alignment.TopEnd).clip(CircleShape)
            .background(directoryBackground).padding(4.dp), color = if (selected) directoryAccent else Color.White, size = 20.sp)
    }
}

@Composable
private fun DirectoryRow(artist: DirectoryArtist, stats: DirectoryStats, prefs: ArtistDirectoryPreferences,
                         selecting: Boolean, selected: Boolean, favorite: Boolean, deps: ArtistScreenDeps, onClick: () -> Unit) {
    val compact = prefs.view == ArtistDirectoryView.COMPACT
    Row(Modifier.fillMaxWidth().height(((if (compact) 38 else 68) * prefs.size.scale).dp.coerceAtLeast(32.dp))
        .clip(RoundedCornerShape(7.dp)).background(if (selected) directoryAccent.copy(alpha = 0.17f) else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) SaltText(if (selected) "✓" else "○", Modifier.width(28.dp), color = directoryAccent)
        if (!compact) {
            DirectoryPortrait(artist, stats, deps, Modifier.size((46 * prefs.size.scale).dp))
            Spacer(Modifier.width(14.dp))
        }
        SaltText((if (favorite) "★ " else "") + artist.name, Modifier.weight(1f), size = (if (compact) 14 else 16).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
        SaltText(if (stats.tracks > 0) "${stats.tracks} / ${stats.albums}" else "—", Modifier.width(110.dp), size = 12.sp, color = directoryMuted)
        SaltText(directoryAddedDate(stats.added), Modifier.width(110.dp), size = 12.sp, color = directoryMuted)
    }
}

private fun directoryAddedDate(time: Long): String = if (time <= 0) "—" else runCatching {
    DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
}.getOrDefault("—")

@Composable
private fun DirectoryButton(label: String, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    SaltText(label, Modifier.clip(RoundedCornerShape(7.dp)).background(if (accent) directoryAccent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        size = 13.sp, color = if (!enabled) directoryMuted.copy(alpha = 0.4f) else if (accent) directoryAccent else Color.White)
}

@Composable
private fun <T> DirectoryChoiceMenu(label: String, choices: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val menuOffset = with(LocalDensity.current) { 40.dp.roundToPx() }
    Box {
        DirectoryButton("$label ▾") { expanded = !expanded }
        if (expanded) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, menuOffset),
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(200.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF323234))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(5.dp)) {
                choices.forEach { choice -> SaltText((if (choice == selected) "✓  " else "    ") + title(choice),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onSelect(choice); expanded = false }
                        .padding(horizontal = 10.dp, vertical = 10.dp), size = 14.sp, color = if (choice == selected) directoryAccent else Color.White) }
            }
        }
    }
}

@Composable
private fun DirectoryDialog(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)).clickable(onClick = close), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF303032)).clickable { }.padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SaltText(title, Modifier.weight(1f), size = 20.sp, weight = FontWeight.Bold, color = Color.White)
                DirectoryButton("关闭", onClick = close)
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}
