package dev.saltartist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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

/** Collect the host's read-only flow without replacing its ViewModel or writing its state. */
@Composable
internal fun ArtistDirectoryHost(deps: ArtistScreenDeps, readyClass: String) {
    DisposableEffect(deps.directory) { onDispose { deps.directory.detach() } }
    val flow = deps.directory.flow ?: return
    LaunchedEffect(flow, readyClass) {
        flow.collect { nativeState ->
            if (nativeState?.javaClass?.name == readyClass) deps.directory.capture(nativeState)
            else deps.directory.reset()
        }
    }
    ArtistDirectoryScreen(deps)
}

/** Sibling overlay in the navigation Box, below the host's mini player. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistDirectoryScreen(deps: ArtistScreenDeps) {
    val source = deps.directory.artists ?: return // Keep the native loading/error screen.
    val browsing = deps.directoryBrowsing
    if (browsing.nativeInterface) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = 10.dp).size(36.dp).clip(RoundedCornerShape(7.dp))
                .background(directoryBackground).semantics { contentDescription = "返回艺人视图" }
                .clickable(role = Role.Button) { browsing.nativeInterface = false }, contentAlignment = Alignment.Center) {
                BackGlyph(Color.White)
            }
        }
        return
    }
    val settings = deps.settings.read()
    val prefs = settings.directory
    val tracks = deps.repository.tracks.orEmpty()
    val stats = remember(tracks) { directoryStatistics(tracks) }
    val favorites = deps.settings.favorites()
    val navigator = deps.navigation.navigator()
    val query = browsing.query
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirming by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var restoreSelected by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf("") }
    val visible = remember(source, prefs, stats, favorites, settings.favoritesFirst, query) {
        orderDirectoryArtists(source, prefs, stats, favorites, settings.favoritesFirst, query)
    }
    val scrollKey = DirectoryScrollKey(prefs, query)
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SaltText("艺术家", size = 24.sp, weight = FontWeight.Bold, color = Color.White)
                SaltText(if (query.isBlank()) "${source.count { !prefs.isHidden(it.name) }} 位艺人" else "${visible.size} 个结果",
                    size = 13.sp, color = directoryMuted)
                Spacer(Modifier.weight(1f))
                DirectorySearch(query, { browsing.query = it }, Modifier.width(280.dp))
                DirectorySettingsMenu(prefs, ::save,
                    onRemove = { selecting = true; selected = emptySet() },
                    onRestore = { restoreSelected = emptySet(); restoring = true }, onNative = { browsing.nativeInterface = true })
            }
            Spacer(Modifier.height(14.dp))
            if (selecting) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectoryButton("取消选择") { selecting = false; selected = emptySet() }
                    DirectoryButton("全选结果") { selected = visible.mapTo(linkedSetOf()) { it.name } }
                    DirectoryButton("移除 (${selected.size})", enabled = selected.isNotEmpty(), accent = true) { confirming = true }
            }
            if (message.isNotEmpty()) SaltText(message, Modifier.padding(top = 8.dp), size = 13.sp, color = directoryAccent)
            Spacer(Modifier.height(12.dp))
            if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SaltText(if (source.isEmpty()) "曲库中还没有艺人" else "没有符合条件的艺人", color = directoryMuted)
            } else if (prefs.view == ArtistDirectoryView.GRID || prefs.view == ArtistDirectoryView.PORTRAITS) {
              key(scrollKey) {
                val initial = remember { browsing.restore(scrollKey, visible) }
                val grid = rememberLazyGridState(initial.index, initial.offset)
                val currentArtists by rememberUpdatedState(visible)
                DisposableEffect(grid, scrollKey) { onDispose {
                    browsing.save(scrollKey, currentArtists, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
                } }
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
              }
            } else {
              key(scrollKey) {
                val initial = remember { browsing.restore(scrollKey, visible) }
                val list = rememberLazyListState(initial.index, initial.offset)
                val currentArtists by rememberUpdatedState(visible)
                DisposableEffect(list, scrollKey) { onDispose {
                    browsing.save(scrollKey, currentArtists, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                } }
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
        }
        if (confirming) DirectoryDialog("移除 ${selected.size} 位艺人？", { confirming = false }) {
            SaltText("仅从艺人列表移除，可在「恢复艺人」中恢复。歌曲和磁盘文件会保留。", color = directoryMuted, size = 14.sp, maxLines = 3)
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectoryButton(if (restoreSelected == prefs.hidden) "取消全选" else "全选") {
                        restoreSelected = if (restoreSelected == prefs.hidden) emptySet() else prefs.hidden
                    }
                    DirectoryButton("恢复所选 (${restoreSelected.size})", enabled = restoreSelected.isNotEmpty(), accent = true) {
                        if (save { it.copy(hidden = it.hidden - restoreSelected) }) restoreSelected = emptySet()
                    }
                    DirectoryButton("全部恢复") { if (save { it.copy(hidden = emptySet()) }) restoreSelected = emptySet() }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth().padding(top = 12.dp)) {
                    items(prefs.hidden.toList().sorted(), key = { it }) { name ->
                        Row(Modifier.fillMaxWidth().semantics { contentDescription = "恢复选择 $name" }.clickable {
                            restoreSelected = if (name in restoreSelected) restoreSelected - name else restoreSelected + name
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            SaltText(if (name in restoreSelected) "✓" else "○", Modifier.width(28.dp), color = directoryAccent)
                            SaltText(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                            DirectoryButton("恢复") {
                                if (save { p -> p.copy(hidden = p.hidden - name) }) restoreSelected = restoreSelected - name
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectorySearch(query: String, onChange: (String) -> Unit, modifier: Modifier) {
    Row(modifier.height(36.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.07f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SearchGlyph(directoryMuted)
        Spacer(Modifier.width(8.dp))
        BasicTextField(query, onChange, Modifier.weight(1f).semantics { contentDescription = "搜索艺人" }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(directoryAccent),
            decorationBox = { field -> Box { if (query.isEmpty()) SaltText("在艺人中搜索", size = 14.sp, color = directoryMuted); field() } })
        if (query.isNotEmpty()) SaltText("×", Modifier.padding(start = 8.dp).clickable { onChange("") }, size = 20.sp, color = directoryMuted)
    }
}

@Composable
private fun DirectoryOption(label: String, selected: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).semantics { this.selected = selected }
        .clickable(role = Role.Button, onClick = click).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        SaltText(label, Modifier.weight(1f), size = 14.sp, color = if (selected) directoryAccent else Color.White)
        if (selected) SaltText("✓", size = 15.sp, color = directoryAccent)
    }
}

@Composable
private fun DirectoryPortrait(artist: DirectoryArtist, stats: DirectoryStats, deps: ArtistScreenDeps, modifier: Modifier) {
    val identity = deps.settings.read().identityPolicy
    val retained = remember(artist.name, identity.artistCacheToken(artist.name), deps.artwork.directoryAvatarRevision(artist.name), artist.coverPath, stats.coverId) {
        DirectoryPortraitMemory<androidx.compose.ui.graphics.ImageBitmap>()
    }
    val portrait = retained.resolve(deps.artwork.artistAvatar(artist.name, identity)) {
        deps.artwork.directoryCover(artist.coverPath)
            ?: stats.coverId.takeIf(String::isNotBlank)?.let { deps.artwork.cover(it)?.image }
    }
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
private fun DirectorySettingsMenu(prefs: ArtistDirectoryPreferences,
                                  save: ((ArtistDirectoryPreferences) -> ArtistDirectoryPreferences) -> Boolean,
                                  onRemove: () -> Unit, onRestore: () -> Unit, onNative: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val menuOffset = with(LocalDensity.current) { 40.dp.roundToPx() }
    Box {
        Row(Modifier.clip(RoundedCornerShape(7.dp)).semantics { contentDescription = "列表选项" }
            .clickable(role = Role.Button) { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SaltText(prefs.sort.label, size = 14.sp, color = Color.White)
            DirectorySortGlyph()
        }
        if (expanded) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, menuOffset),
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(244.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF323234))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(8.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                DirectoryMenuHeading("排序")
                ArtistDirectorySort.entries.forEach { sort -> DirectoryOption(sort.label, sort == prefs.sort) { save { it.copy(sort = sort) } } }
                DirectoryMenuHeading("视图")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).padding(3.dp)) {
                    ArtistDirectoryView.entries.forEach { view ->
                        val active = view == prefs.view
                        Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp)).background(if (active) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                            .semantics { contentDescription = view.label; selected = active }.clickable(role = Role.Button) { save { it.copy(view = view) } }, contentAlignment = Alignment.Center) {
                            DirectoryViewGlyph(view, if (active) directoryAccent else Color.White)
                        }
                    }
                }
                DirectoryMenuHeading("大小")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.04f)).padding(3.dp)) {
                    ArtistDirectorySize.entries.forEach { size ->
                        val active = size == prefs.size
                        Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp)).background(if (active) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                            .semantics { contentDescription = "大小：${size.label}"; selected = active }.clickable(role = Role.Button) { save { it.copy(size = size) } }, contentAlignment = Alignment.Center) {
                            SaltText(size.label, size = 14.sp, color = if (active) directoryAccent else Color.White)
                        }
                    }
                }
                }
                Spacer(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
                DirectoryOption("移除", false) { expanded = false; onRemove() }
                DirectoryOption("恢复 (${prefs.hidden.size})", false) { expanded = false; onRestore() }
                DirectoryOption("原生界面", false) { expanded = false; onNative() }
            }
        }
    }
}

@Composable
private fun DirectorySortGlyph() {
    Canvas(Modifier.size(17.dp)) {
        for (row in 0..2) {
            val y = size.height * (0.22f + row * 0.28f)
            drawCircle(Color.White, size.width * 0.045f, Offset(size.width * 0.1f, y))
            drawLine(Color.White, Offset(size.width * 0.32f, y), Offset(size.width * 0.94f, y), size.width * 0.09f, StrokeCap.Round)
        }
    }
}

@Composable
private fun DirectoryViewGlyph(view: ArtistDirectoryView, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.width * 0.075f
        if (view == ArtistDirectoryView.GRID || view == ArtistDirectoryView.PORTRAITS) {
            for (row in 0..1) for (col in 0..1) {
                val center = Offset(size.width * (0.27f + col * 0.46f), size.height * (0.27f + row * 0.46f))
                if (view == ArtistDirectoryView.PORTRAITS) drawCircle(color, size.width * 0.13f, center)
                else drawRect(color, center - Offset(size.width * 0.13f, size.height * 0.13f), Size(size.width * 0.26f, size.height * 0.26f), style = Stroke(stroke))
            }
        } else for (row in 0..2) {
            val y = size.height * (0.2f + row * 0.3f)
            if (view == ArtistDirectoryView.LIST) drawCircle(color, size.width * 0.075f, Offset(size.width * 0.12f, y))
            drawLine(color, Offset(size.width * (if (view == ArtistDirectoryView.LIST) 0.32f else 0.08f), y), Offset(size.width * 0.95f, y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun DirectoryMenuHeading(label: String) {
    SaltText(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), size = 12.sp, weight = FontWeight.Medium, color = directoryMuted)
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
