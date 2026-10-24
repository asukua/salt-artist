package dev.saltartist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.artwork.ArtistMotionSurface
import dev.saltartist.artwork.nativeMotionEnabled
import dev.saltartist.data.ArtistAlbum
import dev.saltartist.data.ArtistPageModel
import dev.saltartist.data.ArtistTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local-library artist page: cinematic hero, compact catalogue, host playback/navigation. */
@Composable
fun ArtistScreen(artistName: String, deps: ArtistScreenDeps) {
    val settings = deps.settings.read()
    val revision = deps.profiles.revision.value
    val rawModel = remember(artistName, deps.repository.tracks) { deps.repository.model(artistName) }
    val model = remember(rawModel, revision, deps.repository.allTracks) {
        deps.profiles.apply(rawModel, deps.repository.allTracks)
    }
    val customPhoto = remember(artistName, revision) { deps.profiles.photoPath(artistName) }
    // Read asynchronous snapshot caches directly so completed local/cover loads recompose.
    // Enqueue local photos before optional network artwork on the shared executor.
    val resolvedArtwork = if (customPhoto != null) {
        deps.artwork.localPhoto(artistName, customPhoto)
    } else {
        deps.artwork.artworkFor(artistName, rawModel.heroTrackId, settings.languagePolicy, settings.identityPolicy)
    }
    val amArt = deps.artwork.appleArt(artistName, settings.languagePolicy, settings.identityPolicy)
    val artwork = resolvedArtwork ?: amArt?.hero ?: deps.artwork.heroCover(rawModel.heroTrackId)
    val palette = artwork?.palette ?: ArtworkPalette.Fallback
    val motion = amArt?.motion?.takeIf {
        nativeMotionEnabled() && settings.animatedHero && customPhoto == null && artwork != null && artwork === amArt.hero
    }
    val logoFile = remember(artistName, revision) { deps.profiles.logoPath(artistName) }
    val customLogoBitmap = deps.artwork.localLogo(logoFile)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val catalogTopInsetPx = with(LocalDensity.current) { 64.dp.roundToPx() }
    val navigator = deps.navigation.navigator()
    val isFavorite = deps.settings.isFavorite(artistName)
    var searchActive by remember(artistName) { mutableStateOf(false) }
    var searchQuery by remember(artistName) { mutableStateOf("") }
    var editorOpen by remember(artistName) { mutableStateOf(false) }
    var infoOpen by remember(artistName) { mutableStateOf(false) }
    var logoEditorOpen by remember(artistName) { mutableStateOf(false) }
    var matcherOpen by remember(artistName) { mutableStateOf(false) }
    var currentTrackId by remember(artistName) { mutableStateOf("") }

    LaunchedEffect(artistName) {
        deps.repository.ensureLoaded()
        listState.scrollToItem(0)
    }
    LaunchedEffect(artistName) {
        while (isActive) {
            currentTrackId = runCatching { WorkshopApi.playback.getCurrentMediaItem()?.id.orEmpty() }.getOrDefault("")
            delay(1200)
        }
    }

    fun toast(message: String, warning: Boolean = false) {
        runCatching {
            WorkshopApi.ui.toast(message, if (warning) WorkshopApi.Ui.ToastType.Warning else WorkshopApi.Ui.ToastType.Success)
        }
    }
    fun playAt(index: Int) {
        val ok = deps.playback.play(model.tracks, index.coerceIn(0, (model.tracks.size - 1).coerceAtLeast(0)))
        deps.log.log("play index=$index ok=$ok artist=${model.name}")
        if (!ok) toast("无法写入宿主播放队列", warning = true)
    }
    fun playAlbum(album: ArtistAlbum) {
        val ok = deps.playback.play(album.tracks, 0)
        deps.log.log("play album \"${album.title}\" tracks=${album.trackCount} ok=$ok")
        if (!ok) toast("无法写入宿主播放队列", warning = true)
    }
    fun shuffle() {
        if (model.tracks.isEmpty()) return
        val ok = deps.playback.play(model.tracks.shuffled(), 0)
        deps.log.log("shuffle ok=$ok artist=${model.name}")
        if (!ok) toast("无法写入宿主播放队列", warning = true)
    }
    fun toggleFavorite() {
        val nowFavorite = deps.settings.toggleFavorite(artistName)
        deps.log.log("favorite ${if (nowFavorite) "add" else "remove"}: $artistName")
    }
    fun share() {
        val summary = "${model.name} · ${model.trackCount} 首歌曲 / ${model.albumCount} 张专辑"
        val copied = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(summary), null)
        }.isSuccess
        toast(if (copied) "已复制艺人信息" else "复制失败", warning = !copied)
        deps.log.log("share copied=$copied artist=${model.name}")
    }
    fun customHero() {
        val file = pickImageFile() ?: return
        deps.profiles.setPhoto(artistName, file)
        deps.artwork.invalidateArtist(artistName)
        toast("已设置自定义头图")
        deps.log.log("custom hero: ${file.absolutePath}")
    }
    fun chooseCustomLogo() {
        val file = pickImageFile("选择艺人艺术字") ?: return
        scope.launch {
            val valid = withContext(Dispatchers.IO) { deps.artwork.cacheLocalLogo(file) }
            if (valid) {
                deps.profiles.setLogo(artistName, file)
                toast("已设置自定义艺术字")
            } else toast("无法读取这张图片，请选择 PNG 或 WebP 图片", warning = true)
        }
    }
    fun refreshHero() {
        if (deps.profiles.photoPath(artistName) != null) {
            toast("正在使用自定义头图，请在编辑艺人中恢复 Apple Music 头图")
            return
        }
        deps.artwork.invalidateArtist(artistName)
        deps.profiles.revision.value++
        toast("正在刷新头图")
        deps.log.log("refresh hero: apple music cache cleared for $artistName")
    }
    fun toggleSearch() {
        searchActive = !searchActive
        if (!searchActive) searchQuery = ""
        else scope.launch { listState.animateScrollToItem(1, scrollOffset = -catalogTopInsetPx) }
        deps.log.log("search toggled active=$searchActive")
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.backdropDeep)) {
        val heroHeight = (maxHeight * 0.72f).coerceIn(410.dp, 570.dp)
        val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
        val scroll = remember(listState, heroPx) {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) heroPx
                else listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
        val collapse = remember(scroll, heroPx) {
            derivedStateOf { (scroll.value / heroPx).coerceIn(0f, 1f) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 108.dp),
        ) {
            item(key = "hero") {
                ArtistHero(
                    name = model.name,
                    meta = "${model.trackCount} 首歌曲 · ${model.albumCount} 张专辑",
                    palette = palette,
                    artwork = artwork,
                    logo = customLogoBitmap ?: amArt?.logo,
                    scroll = scroll,
                    immersive = settings.immersiveHero,
                    scrollBlur = settings.scrollHeroBlur,
                    height = heroHeight,
                    isFavorite = isFavorite,
                    enabled = model.tracks.isNotEmpty(),
                    onInfo = { infoOpen = true },
                    onPlay = { playAt(0) },
                    onToggleFavorite = ::toggleFavorite,
                    motionContent = motion?.let { clip ->
                        { modifier, blur -> ArtistMotionSurface(clip.url, modifier, deps.log,
                            frameRate = settings.motionFrameRate, blurProgress = blur,
                            resolution = settings.motionResolution, cache = deps.artwork.motionCache) }
                    },
                )
            }
            item(key = "catalogue") {
                ArtistCatalog(
                    model = model,
                    deps = deps,
                    palette = palette,
                    currentTrackId = currentTrackId,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onPlayTrack = { track: ArtistTrack ->
                        val index = model.tracks.indexOfFirst { it.id == track.id }
                        playAt(index.coerceAtLeast(0))
                    },
                    onPlayAlbum = ::playAlbum,
                    onShuffle = ::shuffle,
                )
            }
        }
        ArtistToolbar(
            title = model.name,
            progress = collapse,
            isScrolling = listState.isScrollInProgress,
            palette = palette,
            searchActive = searchActive,
            onBack = {
                if (!deps.navigation.back(navigator)) deps.log.log("navigator.back unavailable")
            },
            onShare = ::share,
            onInfo = { infoOpen = true },
            onSearch = ::toggleSearch,
            onShuffle = ::shuffle,
            onEdit = {
                editorOpen = true
                deps.log.log("open artist editor: ${model.name}")
            },
            onCustomHero = ::customHero,
            onRefreshHero = ::refreshHero,
            onCustomLogo = { logoEditorOpen = true },
            onMatchArtist = { matcherOpen = true },
        )
        if (matcherOpen) ArtistMatcher(artistName, palette, deps,
            onClose = { matcherOpen = false },
            onChanged = {
                deps.artwork.invalidateArtist(artistName)
                deps.profiles.revision.value++
                matcherOpen = false
                toast("已更新 Apple Music 匹配")
            },
        )
        if (infoOpen) ArtistInfo(model, palette, amArt?.avatar, amArt?.details) { infoOpen = false }
        if (logoEditorOpen) ArtistLogoEditor(
            name = model.name,
            logo = customLogoBitmap ?: amArt?.logo,
            isCustom = deps.profiles.hasCustomLogo(artistName),
            palette = palette,
            onChoose = ::chooseCustomLogo,
            onReset = { deps.profiles.setLogo(artistName, null); toast("已恢复默认艺术字") },
            onClose = { logoEditorOpen = false },
        )
        if (editorOpen) {
            // Persist edits against the original route name, even after a display-name change.
            ArtistEditor(rawModel, palette, deps.profiles, deps.repository.allTracks) {
                editorOpen = false
                deps.profiles.revision.value++
            }
        }
    }
}
@Composable
private fun ArtistToolbar(
    title: String,
    progress: State<Float>,
    isScrolling: Boolean,
    palette: ArtworkPalette,
    searchActive: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onSearch: () -> Unit,
    onShuffle: () -> Unit,
    onEdit: () -> Unit,
    onCustomHero: () -> Unit,
    onRefreshHero: () -> Unit,
    onCustomLogo: () -> Unit,
    onMatchArtist: () -> Unit,
) {
    var menuOpen by remember(title) { mutableStateOf(false) }
    LaunchedEffect(isScrolling) { if (isScrolling) menuOpen = false }
    Box(
        Modifier.fillMaxWidth().drawBehind {
            val solid = ((progress.value - 0.60f) / 0.32f).coerceIn(0f, 1f)
            if (solid > 0f) {
                drawRect(Brush.verticalGradient(
                    0f to palette.backdropDeep.copy(alpha = solid * 0.98f),
                    0.80f to palette.backdropDeep.copy(alpha = solid * 0.92f),
                    1f to Color.Transparent,
                ))
            }
        }.padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton("返回", onBack) { BackGlyph(Color.White, 15.dp) }
            Spacer(Modifier.width(14.dp))
            SaltText(
                title, size = 14.sp, weight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.weight(1f).graphicsLayer {
                    alpha = ((progress.value - 0.28f) / 0.30f).coerceIn(0f, 1f)
                },
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            ToolbarButton("搜索歌曲", onSearch, selected = searchActive) { SearchGlyph(Color.White, 16.dp) }
            Spacer(Modifier.width(8.dp))
            ToolbarButton("更多艺人操作", { menuOpen = !menuOpen }, selected = menuOpen) {
                MoreGlyph(Color.White, 17.dp)
            }
        }
        if (menuOpen) {
            val density = LocalDensity.current
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { 40.dp.roundToPx() }),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                Column(
                    Modifier.width(206.dp).clip(RoundedCornerShape(13.dp))
                        .background(palette.backdropDeep.copy(alpha = 0.98f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
                        .padding(vertical = 6.dp),
                ) {
                    val entries = listOf(
                        "艺人信息" to onInfo,
                        "复制艺人信息" to onShare,
                        "随机播放" to onShuffle,
                        "匹配 Apple Music 艺人" to onMatchArtist,
                        "编辑艺人" to onEdit,
                        "更换头图" to onCustomHero,
                        "刷新头图" to onRefreshHero,
                        "自定义艺术字" to onCustomLogo,
                    )
                    entries.forEachIndexed { index, (label, action) ->
                        if (index == 3) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.10f)))
                        Box(
                            Modifier.fillMaxWidth().height(35.dp).clickable {
                                menuOpen = false
                                action()
                            }.padding(horizontal = 15.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SaltText(label, size = 12.sp, color = Color.White.copy(alpha = 0.94f), modifier = Modifier.weight(1f))
                                when (index) {
                                    0 -> InfoGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                    1 -> CopyGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                    2 -> ShuffleGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                    3 -> SearchGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                    4 -> EditGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                    5 -> PhotoGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                    6 -> RefreshGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                    7 -> LetterGlyph(Color.White.copy(alpha = 0.7f), 14.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.24f))
            .border(0.5.dp, Color.White.copy(alpha = if (selected) 0.38f else 0.16f), CircleShape)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
