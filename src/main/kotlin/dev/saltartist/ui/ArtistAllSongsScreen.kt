package dev.saltartist.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.bridge.NativeTrackListBridge
import dev.saltartist.data.ArtistPageModel
import dev.saltartist.data.ArtistTrack
import dev.saltartist.data.formatDuration

/** Use the host's main song list, retaining edited metadata with a lazy table fallback. */
@Composable
internal fun ArtistAllSongsScreen(
    model: ArtistPageModel,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    currentTrackId: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onPlayTrack: (ArtistTrack) -> Unit,
    searchActive: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
) {
    val trimmed = query.trim()
    val tracks = remember(model.tracks, trimmed) { filterArtistSongs(model.tracks, trimmed) }
    val native = remember(deps) {
        NativeTrackListBridge(deps.log, deps.directoryTargets?.screenClass == "androidx.compose.ui.\u0998")
    }
    val originals = deps.repository.nativeTracks
    val hostContext = native.hasHostContext()
    // A late raw-library response must not replace a table the user has already
    // scrolled. A subsequent visit can use the now-ready native component.
    val nativeOnEntry = remember(native, model.name) { hostContext && originals.isNotEmpty() }
    val nativeList = remember(native, tracks, originals, hostContext, trimmed, nativeOnEntry) {
        // Native row playback queues its supplied groups. Searching must retain
        // the full artist queue, as the plugin's existing callback does.
        if (nativeOnEntry && hostContext && trimmed.isBlank()) native.prepare(tracks, originals) else null
    }
    LaunchedEffect(model.name, trimmed) { listState.scrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.backdropDeep)) {
        val sidePadding = if (maxWidth < 560.dp) 20.dp else 32.dp
        val showArtist = maxWidth >= 740.dp
        val showAlbum = maxWidth >= 560.dp
        Box(Modifier.fillMaxSize().padding(horizontal = sidePadding).padding(top = 76.dp),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1280.dp).fillMaxSize()) {
                SaltText("全部歌曲", size = 30.sp, weight = FontWeight.Bold, color = palette.onBackdrop)
                Spacer(Modifier.height(6.dp))
                SaltText("${model.name} · ${if (trimmed.isBlank()) model.trackCount else "${tracks.size}/${model.trackCount}"} 首歌曲",
                    size = 13.sp, color = palette.onBackdrop.copy(alpha = 0.62f))
                Spacer(Modifier.height(18.dp))
                if (searchActive) {
                    CatalogSearch(query, palette, onQueryChange)
                    Spacer(Modifier.height(16.dp))
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (nativeList != null) {
                        key(model.name, trimmed) { nativeList.Content(Modifier.fillMaxSize()) }
                    } else {
                        Column(Modifier.fillMaxSize().padding(end = 12.dp)) {
                            if (tracks.isNotEmpty()) SongTableHeading(palette, showArtist, showAlbum)
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 112.dp)) {
                                if (tracks.isEmpty()) item(key = "empty") {
                                    SaltText(if (trimmed.isBlank()) "曲库中暂无该艺人的歌曲" else "没有匹配的歌曲",
                                        Modifier.padding(vertical = 24.dp), size = 13.sp, color = palette.onBackdrop.copy(alpha = 0.62f))
                                }
                                items(tracks.size, key = { "song:$it:${tracks[it].id}" }, contentType = { "song" }) { index ->
                                    val track = tracks[index]
                                    TrackContextArea(track, deps, palette, { onPlayTrack(track) }) {
                                        SongTableRow(track, index + 1, deps, palette,
                                            currentTrackId.isNotBlank() && track.id == currentTrackId,
                                            showArtist, showAlbum) { onPlayTrack(track) }
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(listState),
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = 35.dp, bottom = 108.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SongTableHeading(palette: ArtworkPalette, showArtist: Boolean, showAlbum: Boolean) {
    Row(Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val color = palette.onBackdrop.copy(alpha = 0.52f)
        SaltText("#", Modifier.width(34.dp), size = 11.sp, color = color)
        SaltText("标题", Modifier.weight(1.7f), size = 11.sp, color = color)
        if (showArtist) SaltText("艺术家", Modifier.weight(1f), size = 11.sp, color = color)
        if (showAlbum) SaltText("专辑", Modifier.weight(1.2f), size = 11.sp, color = color)
        SaltText("时长", Modifier.width(48.dp), size = 11.sp, color = color, align = TextAlign.End)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.onBackdrop.copy(alpha = 0.08f)))
}

@Composable
private fun SongTableRow(
    track: ArtistTrack,
    index: Int,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    current: Boolean,
    showArtist: Boolean,
    showAlbum: Boolean,
    onPlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val foreground = if (current) palette.accent else palette.onBackdrop
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .background(when { current -> palette.accent.copy(alpha = 0.09f)
            hovered -> palette.onBackdrop.copy(alpha = 0.045f); else -> Color.Transparent })
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPlay)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SaltText(index.toString(), Modifier.width(34.dp), size = 11.sp,
            color = if (current) foreground else palette.onBackdrop.copy(alpha = 0.40f))
        Row(Modifier.weight(1.7f).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogCover(track.id, deps, palette, Modifier.size(48.dp), radius = 2, maxSide = 128)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SaltText(track.title, size = 13.sp, color = foreground,
                    weight = if (current) FontWeight.Medium else FontWeight.Normal)
                if (!showArtist || !showAlbum) {
                    Spacer(Modifier.height(2.dp))
                    SaltText(listOfNotNull(track.artist.takeIf { it.isNotBlank() },
                        track.album.takeIf { !showAlbum && it.isNotBlank() }).joinToString(" · "),
                        size = 11.sp, color = palette.onBackdrop.copy(alpha = 0.55f))
                }
            }
        }
        if (showArtist) SaltText(track.artist, Modifier.weight(1f).padding(end = 16.dp), size = 12.sp,
            color = palette.onBackdrop.copy(alpha = 0.65f))
        if (showAlbum) SaltText(track.album, Modifier.weight(1.2f).padding(end = 16.dp), size = 12.sp,
            color = palette.onBackdrop.copy(alpha = 0.65f))
        SaltText(formatDuration(track.durationMs), Modifier.width(48.dp), size = 12.sp,
            color = palette.onBackdrop.copy(alpha = 0.52f), align = TextAlign.End)
    }
}

internal fun filterArtistSongs(tracks: List<ArtistTrack>, query: String): List<ArtistTrack> =
    if (query.isBlank()) tracks else tracks.filter {
        it.title.contains(query, ignoreCase = true) || it.album.contains(query, ignoreCase = true)
    }
