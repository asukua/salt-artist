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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.artwork.Artwork
import dev.saltartist.data.ArtistAlbum
import dev.saltartist.data.ArtistPageModel
import dev.saltartist.data.ArtistTrack
import dev.saltartist.data.formatDuration
import dev.saltartist.data.selectLatestAddedAlbum

internal data class ArtistCatalogPlan(
    val albums: List<ArtistAlbum>, val latest: ArtistAlbum?, val tracks: List<ArtistTrack>,
    val totalCount: Int, val query: String,
    val sidePadding: Dp, val sideBySide: Boolean, val albumColumns: Int,
    val songs: CatalogSongLayout, val showDuration: Boolean,
)

@Composable
internal fun rememberArtistCatalog(model: ArtistPageModel, width: Dp, searchActive: Boolean,
                                   searchQuery: String, page: Int): ArtistCatalogPlan {
    val albums = remember(model.albums) {
        model.albums.sortedWith(compareByDescending<ArtistAlbum> { it.year }.thenBy { it.title }.thenBy { it.artist })
    }
    val latest = remember(model.albums) { selectLatestAddedAlbum(model.albums) }
    val query = if (searchActive) searchQuery.trim() else ""
    val tracks = remember(model.tracks, query) {
        filterArtistSongs(model.tracks, query)
    }
    val padding = if (width < 560.dp) 20.dp else 32.dp
    val innerWidth = (width - padding * 2).coerceAtMost(1280.dp)
    val sideBySide = innerWidth >= 800.dp && albums.isNotEmpty()
    val songWidth = innerWidth - if (sideBySide) 300.dp else 0.dp
    val columns = when { songWidth >= 720.dp -> 3; songWidth >= 470.dp -> 2; else -> 1 }
    val albumColumns = when { innerWidth >= 1100.dp -> 6; innerWidth >= 860.dp -> 5; innerWidth >= 620.dp -> 4; else -> 2 }
    return ArtistCatalogPlan(albums, latest, tracks, model.trackCount, query, padding, sideBySide,
        albumColumns, CatalogSongLayout(tracks.size, columns, page), songWidth / columns >= 270.dp)
}

/** Only the fixed-size overview and visible song/album rows are composed. */
internal fun LazyListScope.artistCatalog(
    plan: ArtistCatalogPlan,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    currentTrackId: String,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onPlayTrack: (ArtistTrack) -> Unit,
    onOpenAlbum: (ArtistAlbum) -> Unit,
    onPlayAlbum: (ArtistAlbum) -> Unit,
    onShuffle: () -> Unit,
    onPageChange: (Int) -> Unit,
    onShowAll: () -> Unit,
) {
    item(key = "catalogue", contentType = "overview") {
        CatalogContainer(plan) {
            Column(Modifier.fillMaxWidth()) {
                @Composable fun songs(modifier: Modifier) = CatalogSongs(
                    plan, deps, palette, currentTrackId, searchActive, searchQuery,
                    onPageChange, onShowAll, onSearchQueryChange, onPlayTrack, onShuffle, modifier,
                )
                if (plan.sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        val album = checkNotNull(plan.latest)
                        LatestAdded(album, deps, palette, { onOpenAlbum(album) }, { onPlayAlbum(album) }, Modifier.width(272.dp))
                        songs(Modifier.weight(1f))
                    }
                } else {
                    plan.latest?.let { album ->
                        LatestAdded(album, deps, palette, { onOpenAlbum(album) }, { onPlayAlbum(album) }, Modifier.widthIn(max = 380.dp).fillMaxWidth())
                        Spacer(Modifier.height(22.dp))
                    }
                    songs(Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (plan.albums.isNotEmpty()) {
        item(key = "album-heading", contentType = "heading") {
            CatalogContainer(plan) {
                Column {
                    Spacer(Modifier.height(26.dp))
                    CatalogSectionTitle("专辑", "${plan.albums.size} 张", palette)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        items((plan.albums.size + plan.albumColumns - 1) / plan.albumColumns,
            key = { "album-row:$it:${plan.albumColumns}" }, contentType = { "album-row" }) { row ->
            CatalogContainer(plan) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        repeat(plan.albumColumns) { column ->
                            val album = plan.albums.getOrNull(row * plan.albumColumns + column)
                            if (album == null) Spacer(Modifier.weight(1f))
                            else CatalogAlbum(album, deps, palette, { onOpenAlbum(album) }, { onPlayAlbum(album) }, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
    item(key = "catalogue-bottom", contentType = "spacing") { Spacer(Modifier.height(24.dp)) }
}

@Composable
private fun CatalogContainer(plan: ArtistCatalogPlan, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = plan.sidePadding), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1280.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun LatestAdded(
    album: ArtistAlbum,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    onOpen: () -> Unit,
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
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen),
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
                        .background(Color.White.copy(alpha = if (hovered) 0.18f else 0.09f))
                        .semantics { contentDescription = "播放专辑" }
                        .clickable(role = Role.Button, onClick = onPlay),
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
    plan: ArtistCatalogPlan,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    currentTrackId: String,
    searchActive: Boolean,
    searchQuery: String,
    onPageChange: (Int) -> Unit,
    onShowAll: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPlayTrack: (ArtistTrack) -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val tracks = plan.tracks
        val totalCount = plan.totalCount
        val query = plan.query
        val layout = plan.songs
        // Query/data/column changes replace the shelf immediately, without replaying a page slide.
        val motion = remember(tracks, query, layout.maxColumns) { CatalogPageMotion(layout.page) }
        LaunchedEffect(motion, layout.page) { motion.showPage(layout.page) }
        // Structural flags belong to this composition. A LaunchedEffect may
        // update them before drawing; reading them in graphicsLayer would move
        // the old page before its replacement layers have been composed.
        val transitioning = motion.isRunning
        val direction = motion.direction
        val shownPage = motion.page
        val countText = if (query.isNotBlank()) "${tracks.size}/$totalCount 首" else "$totalCount 首"
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onShowAll),
                    verticalAlignment = Alignment.CenterVertically) {
                    SaltText("全部歌曲", size = 16.sp, weight = FontWeight.SemiBold, color = palette.onBackdrop)
                    Spacer(Modifier.width(3.dp))
                    ChevronRightGlyph(palette.onBackdrop.copy(alpha = 0.6f), 10.dp)
                }
                Spacer(Modifier.width(5.dp))
                SaltText(countText, size = 11.sp, color = palette.onBackdrop.copy(alpha = 0.58f))
                Spacer(Modifier.weight(1f))
                CatalogShuffle(palette, enabled = totalCount > 0, onShuffle = onShuffle)
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
                Box(Modifier.fillMaxWidth().heightIn(min = if (layout.pageCount > 1) 140.dp else 0.dp)
                    .semantics { stateDescription = "第 ${shownPage + 1} 页，共 ${layout.pageCount} 页" + if (transitioning) "，切换中" else "" }) {
                    Box(Modifier.fillMaxWidth().clipToBounds()) {
                        motion.previousPage?.let { page ->
                            key("outgoing", page) {
                                CatalogSongPage(plan, CatalogSongLayout(tracks.size, layout.maxColumns, page), deps,
                                    palette, currentTrackId, false, onPlayTrack,
                                    Modifier.graphicsLayer { translationX = -direction * motion.fraction * widthPx }
                                        .clearAndSetSemantics { })
                            }
                        }
                        key("current", shownPage) {
                            CatalogSongPage(plan, CatalogSongLayout(tracks.size, layout.maxColumns, shownPage), deps,
                                palette, currentTrackId, !transitioning, onPlayTrack,
                                Modifier.graphicsLayer {
                                    translationX = if (transitioning) direction * (1f - motion.fraction) * widthPx else 0f
                                }
                            )
                        }
                    }
                    val ready = !motion.isRunning && motion.page == layout.page
                    if (layout.hasPrevious) CatalogPageArrow(false, palette, ready, { onPageChange(layout.page - 1) },
                        Modifier.align(Alignment.CenterStart).offset(x = (-20).dp))
                    if (layout.hasNext) CatalogPageArrow(true, palette, ready, { onPageChange(layout.page + 1) },
                        Modifier.align(Alignment.CenterEnd).offset(x = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun CatalogSongPage(plan: ArtistCatalogPlan, layout: CatalogSongLayout, deps: ArtistScreenDeps,
                            palette: ArtworkPalette, currentTrackId: String, enabled: Boolean,
                            onPlayTrack: (ArtistTrack) -> Unit, modifier: Modifier) {
    val compactWidth = (320 * layout.columns + 18 * (layout.columns - 1).coerceAtLeast(0)).dp
    Row(modifier.widthIn(max = compactWidth).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(layout.columns) { column ->
            Column(Modifier.weight(1f)) {
                repeat(layout.rows) { row ->
                    layout.index(row, column)?.let { index ->
                        val track = plan.tracks[index]
                        CatalogTrack(track, deps, palette, currentTrackId.isNotBlank() && track.id == currentTrackId,
                            layout.divider(row, column), plan.showDuration, enabled) { onPlayTrack(track) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogPageArrow(next: Boolean, palette: ArtworkPalette, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier.width(20.dp).height(48.dp).clip(RoundedCornerShape(5.dp))
        .background(palette.onBackdrop.copy(alpha = if (hovered) 0.10f else 0.035f))
        .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
        .semantics { contentDescription = if (next) "下一页歌曲" else "上一页歌曲" }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (next) ChevronRightGlyph(palette.onBackdrop.copy(alpha = 0.72f), 13.dp)
        else BackGlyph(palette.onBackdrop.copy(alpha = 0.72f), 13.dp)
    }
}

@Composable
internal fun CatalogTrack(
    track: ArtistTrack,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    current: Boolean,
    divider: Boolean,
    showDuration: Boolean,
    enabled: Boolean = true,
    onPlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        current -> palette.accent.copy(alpha = 0.10f)
        hovered -> Color.White.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    TrackContextArea(track, deps, palette, onPlay, enabled) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(4.dp)).background(background)
                    .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onPlay)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CatalogCover(
                    track.id, deps, palette,
                    Modifier.size(34.dp).then(
                        if (current) Modifier.border(1.dp, palette.accent.copy(alpha = 0.85f), RoundedCornerShape(3.dp)) else Modifier,
                    ),
                    radius = 3, maxSide = 96,
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
}

@Composable
private fun CatalogAlbum(
    album: ArtistAlbum,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier.clip(RoundedCornerShape(5.dp))
            .hoverable(interaction).pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            CatalogCover(album.coverTrackId, deps, palette, Modifier.fillMaxSize(), radius = 5)
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)))
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(9.dp).size(28.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.48f))
                        .semantics { contentDescription = "播放专辑" }
                        .clickable(role = Role.Button, onClick = onPlay),
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
internal fun CatalogCover(
    trackId: String,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    modifier: Modifier,
    radius: Int,
    maxSide: Int = 640,
) {
    val cover = rememberLibraryCover(trackId, deps, maxSide)
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
internal fun rememberLibraryCover(trackId: String, deps: ArtistScreenDeps, maxSide: Int = 320, hero: Boolean = false): Artwork? {
    if (trackId.isBlank()) return null
    val request = remember(deps.artwork, trackId, maxSide, hero, deps.artwork.coverGeneration) {
        deps.artwork.acquireCover(trackId, maxSide, hero)
    }
    return request.state.value
}

@Composable
internal fun rememberDirectoryCover(path: String?, deps: ArtistScreenDeps): Artwork? {
    val file = deps.artwork.directoryCoverFile(path) ?: return null
    val request = remember(deps.artwork, file, file.lastModified(), file.length(), deps.artwork.coverGeneration) {
        deps.artwork.acquireDirectoryCover(file)
    }
    return request.state.value
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
        Modifier.clip(RoundedCornerShape(4.dp))
            .semantics { contentDescription = "随机播放" }
            .background(Color.White.copy(alpha = if (hovered && enabled) 0.09f else 0f))
            .hoverable(interaction).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = onShuffle),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = palette.onBackdrop.copy(alpha = if (enabled) 0.75f else 0.25f)
            ShuffleGlyph(color, 12.dp)
            Spacer(Modifier.width(5.dp))
            SaltText("随机播放", size = 11.sp, color = color)
        }
    }
}

@Composable
internal fun CatalogSearch(query: String, palette: ArtworkPalette, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    Row(
        Modifier.fillMaxWidth().height(33.dp).clip(RoundedCornerShape(5.dp))
            .background(palette.onBackdrop.copy(alpha = 0.035f))
            .border(1.dp, palette.onBackdrop.copy(alpha = 0.08f), RoundedCornerShape(5.dp))
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
            textStyle = TextStyle(fontFamily = hostFontFamily(), color = palette.onBackdrop, fontSize = 12.sp),
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
