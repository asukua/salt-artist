package dev.saltartist.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.data.ArtistAlbum
import dev.saltartist.data.ArtistPageModel
import dev.saltartist.data.ArtistTrack
import dev.saltartist.data.formatDuration
import dev.saltartist.data.selectLatestAddedAlbum

/** 紧凑的发行与曲库区域，所有播放操作均复用页面提供的宿主队列入口。 */
@Composable
internal fun ArtistCatalog(
    model: ArtistPageModel,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    currentTrackId: String,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onPlayTrack: (ArtistTrack) -> Unit,
    onPlayAlbum: (ArtistAlbum) -> Unit,
    onShuffle: () -> Unit,
) {
    val albums = remember(model.albums) {
        model.albums.sortedWith(
            compareByDescending<ArtistAlbum> { it.year }.thenBy { it.title }.thenBy { it.artist },
        )
    }
    val latestAddedAlbum = remember(model.albums) { selectLatestAddedAlbum(model.albums) }
    val query = if (searchActive) searchQuery.trim() else ""
    val tracks = remember(model.tracks, query) {
        if (query.isBlank()) model.tracks else model.tracks.filter {
            it.title.contains(query, ignoreCase = true) || it.album.contains(query, ignoreCase = true)
        }
    }
    var expanded by remember(model.name) { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sidePadding = if (maxWidth < 560.dp) 20.dp else 32.dp
        Box(
            Modifier.fillMaxWidth().padding(horizontal = sidePadding).padding(bottom = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = 1280.dp).fillMaxWidth()) {
                val overviewSideBySide = maxWidth >= 800.dp && albums.isNotEmpty()
                val albumColumns = when {
                    maxWidth >= 1100.dp -> 6
                    maxWidth >= 860.dp -> 5
                    maxWidth >= 620.dp -> 4
                    else -> 2
                }
                Column(Modifier.fillMaxWidth()) {
                    if (overviewSideBySide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            LatestAdded(
                                album = latestAddedAlbum ?: albums.first(),
                                deps = deps,
                                palette = palette,
                                onPlay = { onPlayAlbum(latestAddedAlbum ?: albums.first()) },
                                modifier = Modifier.width(272.dp),
                            )
                            CatalogSongs(
                                tracks = tracks,
                                totalCount = model.trackCount,
                                deps = deps,
                                palette = palette,
                                currentTrackId = currentTrackId,
                                searchActive = searchActive,
                                searchQuery = searchQuery,
                                query = query,
                                expanded = expanded,
                                onExpand = { expanded = !expanded },
                                onSearchQueryChange = onSearchQueryChange,
                                onPlayTrack = onPlayTrack,
                                onShuffle = onShuffle,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        if (albums.isNotEmpty()) {
                            LatestAdded(
                                album = latestAddedAlbum ?: albums.first(),
                                deps = deps,
                                palette = palette,
                                onPlay = { onPlayAlbum(latestAddedAlbum ?: albums.first()) },
                                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                            )
                            Spacer(Modifier.height(22.dp))
                        }
                        CatalogSongs(
                            tracks = tracks,
                            totalCount = model.trackCount,
                            deps = deps,
                            palette = palette,
                            currentTrackId = currentTrackId,
                            searchActive = searchActive,
                            searchQuery = searchQuery,
                            query = query,
                            expanded = expanded,
                            onExpand = { expanded = !expanded },
                            onSearchQueryChange = onSearchQueryChange,
                            onPlayTrack = onPlayTrack,
                            onShuffle = onShuffle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (albums.isNotEmpty()) {
                        Spacer(Modifier.height(26.dp))
                        CatalogSectionTitle("专辑", "${albums.size} 张", palette)
                        Spacer(Modifier.height(10.dp))
                        albums.chunked(albumColumns).forEach { rowAlbums ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                rowAlbums.forEach { album ->
                                    CatalogAlbum(
                                        album = album,
                                        deps = deps,
                                        palette = palette,
                                        onPlay = { onPlayAlbum(album) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(albumColumns - rowAlbums.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatestAdded(
    album: ArtistAlbum,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(modifier) {
        CatalogSectionTitle("最新添加", "", palette)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = if (hovered) 0.035f else 0f))
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPlay),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogCover(album.coverTrackId, deps, palette, Modifier.size(112.dp), radius = 5)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                // 宿主的 addedTime 是创建时间代理，不能显示成精确的添加日期。
                SaltText("本地资料库", size = 11.sp, color = palette.onBackdrop.copy(alpha = 0.68f))
                Spacer(Modifier.height(5.dp))
                SaltText(album.title, size = 12.sp, weight = FontWeight.Medium, color = palette.onBackdrop, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                SaltText("${album.trackCount} 首歌曲", size = 11.sp, color = palette.onBackdrop.copy(alpha = 0.68f))
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.size(25.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = if (hovered) 0.18f else 0.09f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayGlyph(palette.onBackdrop, 10.dp)
                }
            }
        }
    }
}

@Composable
private fun CatalogSongs(
    tracks: List<ArtistTrack>,
    totalCount: Int,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    currentTrackId: String,
    searchActive: Boolean,
    searchQuery: String,
    query: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPlayTrack: (ArtistTrack) -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val availableWidth = maxWidth
        val maxColumns = when {
            availableWidth >= 720.dp -> 3
            availableWidth >= 470.dp -> 2
            else -> 1
        }
        val showDuration = availableWidth / maxColumns >= 270.dp
        val previewCount = maxColumns * 3
        val showAllMatches = query.isNotBlank()
        val shown = if (expanded || showAllMatches) tracks else tracks.take(previewCount)
        val rowsPerColumn = if (expanded || showAllMatches) {
            ((shown.size + maxColumns - 1) / maxColumns).coerceAtLeast(1)
        } else 3
        val columns = shown.chunked(rowsPerColumn)
        val compactWidth = (320 * columns.size + 18 * (columns.size - 1).coerceAtLeast(0)).dp
        val countText = if (query.isNotBlank()) "${tracks.size}/$totalCount 首" else "$totalCount 首"
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                SaltText("歌曲", size = 16.sp, weight = FontWeight.SemiBold, color = palette.onBackdrop)
                Spacer(Modifier.width(5.dp))
                CatalogShuffle(palette, enabled = totalCount > 0, onShuffle = onShuffle)
                Spacer(Modifier.width(5.dp))
                SaltText(countText, size = 11.sp, color = palette.onBackdrop.copy(alpha = 0.58f))
                Spacer(Modifier.weight(1f))
                if (tracks.size > previewCount && !showAllMatches) {
                    SaltText(
                        if (expanded) "收起" else "显示全部",
                        size = 11.sp,
                        color = palette.onBackdrop.copy(alpha = 0.75f),
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                            .clickable(role = Role.Button, onClick = onExpand).padding(vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            if (searchActive) {
                CatalogSearch(searchQuery, palette, onSearchQueryChange)
                Spacer(Modifier.height(7.dp))
            }
            if (tracks.isEmpty()) {
                SaltText(
                    if (query.isNotBlank()) "没有匹配的歌曲" else "曲库中暂无该艺人的歌曲",
                    size = 12.sp,
                    color = palette.onBackdrop.copy(alpha = 0.65f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                Row(
                    Modifier.widthIn(max = compactWidth).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    columns.forEach { columnTracks ->
                        Column(Modifier.weight(1f)) {
                            columnTracks.forEachIndexed { index, track ->
                                CatalogTrack(
                                    track = track,
                                    deps = deps,
                                    palette = palette,
                                    current = currentTrackId.isNotBlank() && track.id == currentTrackId,
                                    divider = index < columnTracks.lastIndex,
                                    showDuration = showDuration,
                                    onPlay = { onPlayTrack(track) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogTrack(
    track: ArtistTrack,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    current: Boolean,
    divider: Boolean,
    showDuration: Boolean,
    onPlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        current -> palette.accent.copy(alpha = 0.10f)
        hovered -> Color.White.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(46.dp)
                .clip(RoundedCornerShape(4.dp)).background(background)
                .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPlay)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogCover(
                track.id, deps, palette,
                Modifier.size(34.dp).then(
                    if (current) Modifier.border(1.dp, palette.accent.copy(alpha = 0.85f), RoundedCornerShape(3.dp)) else Modifier,
                ),
                radius = 3,
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                SaltText(
                    track.title,
                    size = 12.sp,
                    weight = if (current) FontWeight.Medium else FontWeight.Normal,
                    color = if (current) palette.accent else palette.onBackdrop,
                )
                Spacer(Modifier.height(2.dp))
                SaltText(
                    track.album.ifBlank { track.artist },
                    size = 11.sp,
                    color = palette.onBackdrop.copy(alpha = 0.62f),
                )
            }
            if (showDuration) {
                Spacer(Modifier.width(7.dp))
                SaltText(formatDuration(track.durationMs), size = 10.sp, color = palette.onBackdrop.copy(alpha = 0.48f))
            }
        }
        if (divider) {
            Box(Modifier.fillMaxWidth().padding(start = 45.dp).height(1.dp).background(Color.White.copy(alpha = 0.07f)))
        }
    }
}

@Composable
private fun CatalogAlbum(
    album: ArtistAlbum,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier.clip(RoundedCornerShape(5.dp))
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPlay),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            CatalogCover(album.coverTrackId, deps, palette, Modifier.fillMaxSize(), radius = 5)
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)))
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(9.dp).size(28.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayGlyph(Color.White, 12.dp)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        SaltText(album.title, size = 12.sp, color = palette.onBackdrop, maxLines = 2)
        Spacer(Modifier.height(3.dp))
        SaltText(
            buildList {
                if (album.year > 0) add(album.year.toString())
                add("${album.trackCount} 首")
            }.joinToString(" · "),
            size = 11.sp,
            color = palette.onBackdrop.copy(alpha = 0.62f),
        )
    }
}

@Composable
private fun CatalogCover(
    trackId: String,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    modifier: Modifier,
    radius: Int,
) {
    val cover = if (trackId.isBlank()) null else deps.artwork.cover(trackId)
    Box(
        modifier.clip(RoundedCornerShape(radius.dp)).background(Color.White.copy(alpha = 0.045f)),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(cover.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            PlayGlyph(palette.onBackdrop.copy(alpha = 0.20f), 14.dp)
        }
    }
}

@Composable
private fun CatalogSectionTitle(title: String, count: String, palette: ArtworkPalette) {
    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        SaltText(title, size = 16.sp, weight = FontWeight.SemiBold, color = palette.onBackdrop)
        if (count.isNotEmpty()) {
            Spacer(Modifier.width(9.dp))
            SaltText(count, size = 11.sp, color = palette.onBackdrop.copy(alpha = 0.58f))
        }
    }
}

@Composable
private fun CatalogShuffle(palette: ArtworkPalette, enabled: Boolean, onShuffle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(25.dp).clip(CircleShape)
            .semantics { contentDescription = "随机播放" }
            .background(Color.White.copy(alpha = if (hovered && enabled) 0.09f else 0f))
            .hoverable(interaction).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = onShuffle),
        contentAlignment = Alignment.Center,
    ) {
        ShuffleGlyph(palette.onBackdrop.copy(alpha = if (enabled) 0.73f else 0.25f), 12.dp)
    }
}

@Composable
private fun CatalogSearch(query: String, palette: ArtworkPalette, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        Modifier.fillMaxWidth().height(33.dp).clip(RoundedCornerShape(5.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(5.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph(palette.onBackdrop.copy(alpha = 0.5f), 12.dp)
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            textStyle = TextStyle(color = palette.onBackdrop, fontSize = 12.sp),
            cursorBrush = SolidColor(palette.accent),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    SaltText("搜索歌曲或专辑", size = 12.sp, color = palette.onBackdrop.copy(alpha = 0.46f))
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            SaltText(
                "清除", size = 11.sp, color = palette.onBackdrop.copy(alpha = 0.68f),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) { onQueryChange("") }.padding(vertical = 5.dp),
            )
        }
    }
}
