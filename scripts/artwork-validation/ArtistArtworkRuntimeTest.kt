@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.saltartist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.saltartist.PluginLog
import dev.saltartist.artwork.Artwork
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.artwork.ArtworkStore
import dev.saltartist.artwork.DemandArtworkCache
import dev.saltartist.bridge.NavigationBridge
import dev.saltartist.bridge.PlaybackBridge
import dev.saltartist.data.*
import dev.saltartist.settings.ArtistProfileStore
import dev.saltartist.settings.ArtistSettings
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.WeakHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ArtistArtworkRuntimeTest {
    private val output = File(System.getProperty("artwork.verification.dir")).apply { mkdirs() }
    private val animationOutput = File(output, "animation").apply { mkdirs() }
    private val log = PluginLog(null)
    private val sceneTimes = WeakHashMap<ImageComposeScene, Long>()

    @Test fun realDecoderKeeps1500CoversAndRestoresThemOfflineWithoutQuotas() {
        val directory = Files.createTempDirectory(output.toPath(), "covers-").toFile()
        val calls = AtomicInteger()
        val bytes = png()
        val store = ArtworkStore(directory, log)
        store.libraryCoverSource = { calls.incrementAndGet(); CompletableFuture.completedFuture(bytes) }
        val requests = (0 until 1500).map { store.acquireCover("song-$it", 96) }
        try {
            val images = requests.map(::awaitImage)
            assertEquals(1500, calls.get())
            assertEquals(1500, directory.resolve("library-covers").listFiles()!!.size)
            assertTrue(images.all { it.image.width == 96 && it.image.height == 96 })
            requests.forEachIndexed { index, request -> assertSame(images[index], request.state.value) }
            requests.forEach { it.close() }
            store.shutdown()
            val offline = ArtworkStore(directory, log)
            offline.libraryCoverSource = { error("Offline restoration must not call the host") }
            try {
                for (id in listOf(0, 127, 128, 512, 513, 1499)) {
                    offline.acquireCover("song-$id", 96).use { assertEquals(96, awaitImage(it).image.width) }
                }
                offline.libraryCoverSource = { CompletableFuture.completedFuture(bytes) }
                offline.acquireCover("song-1499", 640, hero = true).use { request ->
                    assertEquals(5, awaitImage(request).blurred.size)
                }
                assertEquals(1501, offline.clearAppleMusicCache().get(10, TimeUnit.SECONDS))
                assertEquals(0, directory.resolve("library-covers").listFiles()!!.size)
            } finally { offline.shutdown() }
            File(output, "covers.txt").writeText("1500 real PNG thumbnails decoded and retained; no 128-item or 512-request quota. Six boundary covers restored after restart with the host source disabled.\n")
        } finally {
            requests.forEach { it.close() }; store.shutdown()
            check(directory.toPath().toRealPath().parent == output.toPath().toRealPath() && directory.name.startsWith("covers-"))
            directory.deleteRecursively()
        }
    }

    @Test fun previewKeepsOriginalSongAndAlbumCoordinatesAcrossWidths() {
        val model = model(36, blankIds = true)
        val deps = deps(ArtworkStore(null, log))
        try {
            for (width in listOf(500, 1000, 1400)) {
                val legacy = renderPreview(model, deps, width, legacy = true)
                val current = renderPreview(model, deps, width, legacy = false)
                assertEquals(legacy, current, "Song and album positions changed at width=$width")
            }
            File(output, "song-pages-layout.txt").writeText("Actual CPU render: song and album text bounds match at widths 500, 1000 and 1400. Navigation controls intentionally differ.\n")
        } finally { deps.artwork.shutdown(); deps.settings.close() }
    }

    @Test fun songShelfPagesWithoutExpandingAndSearchFindsTheLastTrack() {
        val store = ArtworkStore(null, log)
        val bytes = png()
        val calls = AtomicInteger()
        store.libraryCoverSource = { calls.incrementAndGet(); CompletableFuture.completedFuture(bytes) }
        val deps = deps(store)
        val model = model(5000)
        val page = mutableStateOf(0)
        val query = mutableStateOf("")
        var showAllCalls = 0
        var shuffleCalls = 0
        var playCalls = 0
        var plan: ArtistCatalogPlan? = null
        val scene = ImageComposeScene(1400, 850) {
            plan = rememberArtistCatalog(model, 1400.dp, true, query.value, page.value)
            LazyColumn(Modifier.fillMaxSize().background(ArtworkPalette.Fallback.backdropDeep)) {
                artistCatalog(checkNotNull(plan), deps, ArtworkPalette.Fallback, "song-1", true, query.value,
                    { query.value = it; page.value = 0 }, { playCalls++ }, {}, {}, { shuffleCalls++ },
                    { page.value = it }, { showAllCalls++ })
            }
        }
        try {
            renderFrames(scene)
            assertEquals((0..8).map { "song-$it" }.toSet(), songNodes(scene).toSet())
            val initialBounds = songBounds(scene)
            val slot = textBounds(scene, "song-0")
            // During a reverse slide the leading column starts outside the
            // clip, so track the rightmost column's visible intermediate slot.
            val reverseSlot = textBounds(scene, "song-6")
            val songRegion = songRegion(scene)
            save(scene, File(animationOutput, "shelf-next-000.png"))
            clickDescription(scene, "下一页歌曲")
            recordShelfTransition(scene, "shelf-next", "song-9", "song-0", slot, songRegion, forward = true) {
                assertTrue(descriptionNode(scene, "下一页歌曲").config.contains(SemanticsProperties.Disabled))
                assertTrue(descriptionNode(scene, "上一页歌曲").config.contains(SemanticsProperties.Disabled))
                clickDescription(scene, "下一页歌曲")
                clickAt(scene, textBounds(scene, "song-9").center)
                assertEquals(1, page.value, "A second page request must be disabled during the slide")
                assertEquals(0, playCalls, "Sliding songs must not start playback")
            }
            assertEquals((9..17).map { "song-$it" }.toSet(), songNodes(scene).toSet())
            assertSlot(slot, textBounds(scene, "song-9"))
            save(scene, File(output, "shelf-page-2.png"))
            save(scene, File(animationOutput, "shelf-prev-000.png"))
            clickDescription(scene, "上一页歌曲")
            recordShelfTransition(scene, "shelf-prev", "song-6", "song-9", reverseSlot, songRegion, forward = false)
            assertEquals(0, checkNotNull(plan).songs.page)
            assertEquals(initialBounds, songBounds(scene), "The spring must finish at the exact original shelf slots")
            clickDescription(scene, "随机播放")
            assertEquals(1, shuffleCalls)
            assertEquals(0, showAllCalls, "The right-hand action must only shuffle")
            assertEquals(0, playCalls)
            clickText(scene, "全部歌曲")
            assertEquals(1, showAllCalls)
            assertEquals(1, shuffleCalls, "The song heading must only open the separate song page")
            assertEquals(0, playCalls)
            clickDescription(scene, "下一页歌曲")
            repeat(4) { renderFrame(scene) }
            assertTrue(shelfIsMoving(scene))
            query.value = "song-4999"; page.value = 0
            renderFrames(scene)
            assertEquals(listOf("song-4999"), songNodes(scene))
            assertFalse(shelfIsMoving(scene), "A new search must discard the previous outgoing page")
            assertTrue(calls.get() < 80, "Shelf paging must not load all 5000 covers")
        } finally { scene.close(); store.shutdown(); deps.settings.close() }
    }

    @Test fun separateSongPageRemainsLazyAndPlaysOriginalTracksAfterSearchAndScroll() {
        val store = ArtworkStore(null, log)
        val bytes = png()
        val calls = AtomicInteger()
        store.libraryCoverSource = { calls.incrementAndGet(); CompletableFuture.completedFuture(bytes) }
        val deps = deps(store)
        val model = model(5000)
        val state = LazyListState()
        val query = mutableStateOf("")
        var played: ArtistTrack? = null
        val scene = ImageComposeScene(1400, 850) {
            ArtistAllSongsScreen(model, deps, ArtworkPalette.Fallback, "song-4000", query.value,
                { query.value = it }, { played = it }, searchActive = true, listState = state)
        }
        try {
            renderFrames(scene)
            assertEquals(5000, state.layoutInfo.totalItemsCount)
            assertTrue(songNodes(scene).size < 40)
            assertFalse(textNodes(scene).contains("最新添加"))
            runBlocking { state.scrollToItem(4000) }
            renderFrames(scene)
            assertTrue(songNodes(scene).contains("song-4000"))
            clickText(scene, "song-4000")
            assertSame(model.tracks[4000], played)
            query.value = "song-4999"
            renderFrames(scene)
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(listOf("song-4999"), songNodes(scene))
            clickText(scene, "song-4999")
            assertSame(model.tracks.last(), played)
            save(scene, File(output, "all-songs-search.png"))
            query.value = "missing"
            renderFrames(scene)
            assertTrue(textNodes(scene).contains("没有匹配的歌曲"))
            assertTrue(calls.get() < 100)
        } finally { scene.close(); store.shutdown(); deps.settings.close() }
    }

    @Test fun replacingSongsOrChangingColumnsDiscardsThePreviousShelfAnimation() {
        val store = ArtworkStore(null, log)
        val deps = deps(store)
        val source = mutableStateOf(model(40, blankIds = true))
        val width = mutableStateOf(1400)
        val page = mutableStateOf(0)
        var plan: ArtistCatalogPlan? = null
        val scene = ImageComposeScene(1400, 850) {
            plan = rememberArtistCatalog(source.value, width.value.dp, false, "", page.value)
            Box(Modifier.width(width.value.dp)) {
                LazyColumn(Modifier.fillMaxSize().background(ArtworkPalette.Fallback.backdropDeep)) {
                    artistCatalog(checkNotNull(plan), deps, ArtworkPalette.Fallback, "", false, "", {}, {}, {}, {}, {},
                        { page.value = it }, {})
                }
            }
        }
        try {
            renderFrames(scene)
            clickDescription(scene, "下一页歌曲")
            repeat(4) { renderFrame(scene) }
            assertTrue(shelfIsMoving(scene))
            source.value = source.value.copy(tracks = source.value.tracks.mapIndexed { index, track ->
                track.copy(title = "replacement-$index")
            })
            page.value = 0
            repeat(4) { renderFrame(scene) }
            assertFalse(shelfIsMoving(scene), "Replacing the source must not animate from stale songs")
            assertTrue(songNodes(scene).isEmpty())
            assertEquals((0..8).map { "replacement-$it" }.toSet(),
                textNodes(scene).filter { it.startsWith("replacement-") }.toSet())

            clickDescription(scene, "下一页歌曲")
            repeat(4) { renderFrame(scene) }
            assertTrue(shelfIsMoving(scene))
            width.value = 500
            repeat(4) { renderFrame(scene) }
            assertFalse(shelfIsMoving(scene), "Changing columns must replace the old shelf without retaining its translation")
            assertEquals(1, checkNotNull(plan).songs.maxColumns)
            assertEquals((3..5).map { "replacement-$it" }.toSet(),
                textNodes(scene).filter { it.startsWith("replacement-") }.toSet())
        } finally { scene.close(); store.shutdown(); deps.settings.close() }
    }

    @Test fun secondaryClickOpensFallbackSongActionsWithoutStartingPlayback() {
        val store = ArtworkStore(null, log)
        val deps = deps(store)
        val model = model(2)
        var played: ArtistTrack? = null
        var playCalls = 0
        val scene = ImageComposeScene(1000, 700) {
            ArtistAllSongsScreen(model, deps, ArtworkPalette.Fallback, "", "", {}, {
                played = it; playCalls++
            })
        }
        try {
            renderFrames(scene)
            assertTrue(textNodes(scene).containsAll(listOf("标题", "艺术家", "专辑", "时长")))
            clickAt(scene, textBounds(scene, "song-0").center, PointerButton.Secondary)
            renderFrames(scene)
            assertEquals(0, playCalls, "Opening a context menu must not activate the song")
            assertNull(played)
            assertTrue(textNodes(scene).containsAll(listOf("播放", "打开专辑", "复制歌曲信息")),
                "ImageComposeScene must expose the actual fallback popup, including its song actions")
            save(scene, File(output, "song-context-fallback.png"))
            clickText(scene, "播放")
            renderFrames(scene)
            assertEquals(1, playCalls)
            assertSame(model.tracks.first(), played)
        } finally { scene.close(); store.shutdown(); deps.settings.close() }
    }

    @Test fun directoryDownloadIconOpensThePanelWithoutATitleBarLabel() {
        class EmptyReady(val groups: List<Any> = emptyList())
        val deps = deps(ArtworkStore(null, log))
        deps.directory.capture(EmptyReady())
        val scene = ImageComposeScene(1000, 700) { ArtistDirectoryScreen(deps) }
        try {
            renderFrames(scene)
            assertTrue("曲库中还没有艺人" in textNodes(scene))
            assertFalse("背景" in textNodes(scene) || hasDescription(scene, "背景"))
            val download = descriptionNode(scene, "一键全部下载")
            assertTrue(download.config.getOrElse(SemanticsProperties.Text) { emptyList() }.isEmpty())
            assertFalse("一键全部下载" in textNodes(scene))
            assertEquals(36f, download.boundsInRoot.width, 0.5f)
            assertEquals(36f, download.boundsInRoot.height, 0.5f)

            clickAt(scene, download.boundsInRoot.center)
            renderFrames(scene)
            assertTrue("全部艺人资料下载" in textNodes(scene))
            assertTrue("开始全部下载" in textNodes(scene))
            assertFalse(deps.preparingDownloads.value)
            assertFalse(deps.artwork.batchDownloads.progress.running)
        } finally { scene.close(); deps.close() }
    }

    @Test fun actualArtistScreenOpensAllSongsAndReturnsToTheSameShelfPageAndPosition() {
        val store = ArtworkStore(null, log)
        val bytes = png()
        store.libraryCoverSource = { CompletableFuture.completedFuture(bytes) }
        val deps = deps(store)
        val source = model(100)
        val field = ArtistRepository::class.java.getDeclaredField("tracksState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(deps.repository) as androidx.compose.runtime.MutableState<List<ArtistTrack>?>).value = source.tracks
        val scene = ImageComposeScene(1400, 1000) { ArtistScreen("Artist", deps) }
        try {
            renderFrames(scene)
            clickDescription(scene, "下一页歌曲")
            renderFrames(scene)
            val before = songBounds(scene)
            assertTrue(before.keys.contains("song-9"))
            save(scene, File(animationOutput, "screen-push-000.png"))
            clickText(scene, "全部歌曲")
            repeat(5) { index -> renderFrame(scene, File(animationOutput, "screen-push-${(index + 1).toString().padStart(3, '0')}.png")) }
            val incomingTop = fullSongHeading(scene).boundsInRoot.top
            renderFrames(scene)
            val finalHeading = fullSongHeading(scene).boundsInRoot
            assertTrue(incomingTop > finalHeading.top + 1f, "Push must include an actual downward-offset intermediate frame")
            assertTrue(textNodes(scene).contains("全部歌曲"))
            assertFalse(textNodes(scene).contains("最新添加"))
            assertNull(searchField(scene), "All songs opens without a search field")
            save(scene, File(output, "artist-all-songs.png"))
            save(scene, File(animationOutput, "screen-push-end.png"))
            save(scene, File(animationOutput, "screen-pop-000.png"))
            clickDescription(scene, "返回")
            repeat(5) { index -> renderFrame(scene, File(animationOutput, "screen-pop-${(index + 1).toString().padStart(3, '0')}.png")) }
            assertTrue(textNodes(scene).contains("最新添加"), "The returning page must already be composed during pop")
            assertTrue(nodes(scene).none { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "全部歌曲" } &&
                    node.boundsInRoot.height > 28f
            }, "The outgoing song page must not expose its actions while it is fading out")
            renderFrames(scene)
            assertTrue(textNodes(scene).contains("最新添加"))
            assertEquals(before, songBounds(scene))
            save(scene, File(animationOutput, "screen-pop-end.png"))

            clickDescription(scene, "搜索歌曲")
            renderFrames(scene)
            setSearchText(scene, "song-19")
            renderFrames(scene)
            assertEquals(listOf("song-19"), songNodes(scene))

            clickText(scene, "全部歌曲")
            renderFrames(scene)
            assertNull(searchField(scene), "The artist's open search must not carry over to all songs")
            assertTrue("song-0" in songNodes(scene), "All songs must initially show the unfiltered list")

            clickDescription(scene, "搜索歌曲")
            renderFrames(scene)
            assertEquals("", focusedSearchField(scene).config[SemanticsProperties.EditableText].text)
            setSearchText(scene, "song-84")
            renderFrames(scene)
            assertEquals(listOf("song-84"), songNodes(scene))

            clickDescription(scene, "搜索歌曲")
            renderFrames(scene)
            assertNull(searchField(scene), "Pressing search again must hide the field")
            assertTrue("song-0" in songNodes(scene), "Closing search must clear the filter")

            clickDescription(scene, "搜索歌曲")
            renderFrames(scene)
            assertEquals("", focusedSearchField(scene).config[SemanticsProperties.EditableText].text)
            assertTrue("song-0" in songNodes(scene), "Reopening search must not restore the cleared filter")
            setSearchText(scene, "song-67")
            renderFrames(scene)
            assertEquals(listOf("song-67"), songNodes(scene))

            clickDescription(scene, "返回")
            renderFrames(scene)
            val artistSearch = assertNotNull(searchField(scene), "Returning must keep the artist's search open")
            assertEquals("song-19", artistSearch.config[SemanticsProperties.EditableText].text)
            assertEquals(listOf("song-19"), songNodes(scene))

            clickText(scene, "全部歌曲")
            renderFrames(scene)
            assertNull(searchField(scene), "Reentering all songs must start with search hidden")
            assertTrue("song-0" in songNodes(scene), "Reentering must discard the previous all-songs query")
        } finally { scene.close(); store.shutdown(); deps.settings.close() }
    }

    @Test fun actualPageTransitionSlidesBothDirectionsAndUnmountsOutgoingPages() {
        val allSongs = mutableStateOf(false)
        val coordinates = HashMap<Boolean, LayoutCoordinates>()
        val scene = ImageComposeScene(240, 280) {
            ArtistPageTransition(allSongs.value) { shown ->
                DisposableEffect(shown) { onDispose { coordinates.remove(shown) } }
                Box(Modifier.fillMaxSize().background(if (shown) Color(0xFF3675AF) else Color.White)
                    .onGloballyPositioned { coordinates[shown] = it }
                    .semantics { contentDescription = if (shown) "song-page-probe" else "artist-page-probe" })
            }
        }
        try {
            renderFrames(scene)
            assertEquals(0f, checkNotNull(coordinates[false]).localToRoot(Offset.Zero).y, 0.5f)
            allSongs.value = true
            repeat(5) { frame -> renderFrame(scene, File(animationOutput, "page-probe-push-${frame.toString().padStart(3, '0')}.png")) }
            val incomingY = checkNotNull(coordinates[true]).localToRoot(Offset.Zero).y
            assertTrue(incomingY in 1f..149f, "The incoming page must be measured between its 150px start and final position: $incomingY")
            assertTrue(checkNotNull(coordinates[false]).isAttached, "The old page must remain mounted during push")
            assertFalse(hasDescription(scene, "artist-page-probe"))
            renderFrames(scene)
            assertEquals(0f, checkNotNull(coordinates[true]).localToRoot(Offset.Zero).y, 0.5f)
            assertFalse(coordinates.containsKey(false), "The outgoing artist page must be disposed after push")
            save(scene, File(animationOutput, "page-probe-push-end.png"))

            allSongs.value = false
            repeat(5) { frame -> renderFrame(scene, File(animationOutput, "page-probe-pop-${frame.toString().padStart(3, '0')}.png")) }
            val outgoingY = checkNotNull(coordinates[true]).localToRoot(Offset.Zero).y
            assertTrue(outgoingY in 1f..149f, "The outgoing page must actually move toward its 150px exit: $outgoingY")
            assertFalse(hasDescription(scene, "song-page-probe"))
            assertTrue(hasDescription(scene, "artist-page-probe"))
            renderFrames(scene)
            assertFalse(coordinates.containsKey(true), "The outgoing song page must be disposed after pop")
            assertEquals(0f, checkNotNull(coordinates[false]).localToRoot(Offset.Zero).y, 0.5f)
            save(scene, File(animationOutput, "page-probe-pop-end.png"))
        } finally { scene.close() }
    }

    @Test fun overlayFadesThroughRealAlphaAndRemainsMountedUntilExitCompletes() {
        val visible = mutableStateOf(false)
        var mounted = false
        val scene = ImageComposeScene(180, 140) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ArtistOverlayTransition(visible.value) {
                    DisposableEffect(Unit) { mounted = true; onDispose { mounted = false } }
                    Box(Modifier.fillMaxSize().background(Color.White)
                        .semantics { contentDescription = "overlay-probe" })
                }
            }
        }
        fun brightness(file: File): Int = ImageIO.read(file).getRGB(40, 40) and 0xff
        try {
            renderFrames(scene)
            val closed = File(animationOutput, "overlay-open-000.png")
            save(scene, closed)
            assertEquals(0, brightness(closed))
            assertFalse(mounted)
            visible.value = true
            val enter = (1..6).map { frame ->
                val file = File(animationOutput, "overlay-open-${frame.toString().padStart(3, '0')}.png")
                renderFrame(scene, file); brightness(file)
            }
            assertTrue(mounted)
            assertTrue(hasDescription(scene, "overlay-probe"))
            assertTrue(enter.any { it in 1..254 }, "Fade-in must render a translucent intermediate frame: $enter")
            assertTrue(enter.last() > enter.first(), "Fade-in brightness must rise across frames: $enter")
            renderFrames(scene)
            val opened = File(animationOutput, "overlay-open-end.png")
            save(scene, opened)
            assertEquals(255, brightness(opened))

            visible.value = false
            val exit = (1..6).map { frame ->
                val file = File(animationOutput, "overlay-close-${frame.toString().padStart(3, '0')}.png")
                renderFrame(scene, file)
                assertTrue(mounted, "The overlay must remain mounted through its fade-out")
                brightness(file)
            }
            assertFalse(hasDescription(scene, "overlay-probe"), "A closing overlay must immediately stop exposing actions")
            assertTrue(exit.any { it in 1..254 }, "Fade-out must render a translucent intermediate frame: $exit")
            assertTrue(exit.last() < exit.first(), "Fade-out brightness must fall across frames: $exit")
            renderFrames(scene)
            val finished = File(animationOutput, "overlay-close-end.png")
            save(scene, finished)
            assertEquals(0, brightness(finished))
            assertFalse(mounted, "The overlay must be unmounted after its exit completes")
        } finally { scene.close() }
    }

    private fun renderPreview(model: ArtistPageModel, deps: ArtistScreenDeps, width: Int, legacy: Boolean): Map<String, List<String>> {
        val scene = ImageComposeScene(width, 900) {
            val plan = rememberArtistCatalog(model, width.dp, false, "", 0)
            LazyColumn(Modifier.fillMaxSize().background(ArtworkPalette.Fallback.backdropDeep)) {
                item { Spacer(Modifier.height(40.dp)) }
                if (legacy) item {
                    LegacyArtistCatalog(model, deps, ArtworkPalette.Fallback, "", false, "", {}, {}, {}, {}, {})
                } else artistCatalog(plan, deps, ArtworkPalette.Fallback, "", false, "", {}, {}, {}, {}, {}, {}, {})
            }
        }
        try {
            renderFrames(scene)
            save(scene, File(output, "${if (legacy) "old" else "paged"}-preview-$width.png"))
            return nodes(scene).flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .filter { it.text.startsWith("song-") || it.text.startsWith("album-") }
                .map { it.text to node.boundsInRoot.toString() } }.groupBy({ it.first }, { it.second })
        } finally { scene.close() }
    }

    private fun songBounds(scene: ImageComposeScene): Map<String, String> = nodes(scene).flatMap { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.filter { it.text.startsWith("song-") }
            .map { it.text to node.boundsInRoot.toString() }
    }.toMap()

    private fun songRegion(scene: ImageComposeScene): Rect {
        val bounds = nodes(scene).filter { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.startsWith("song-") }
        }.map { it.boundsInRoot }
        return Rect(bounds.minOf { it.left } - 50f, bounds.minOf { it.top } - 16f,
            bounds.maxOf { it.right } + 12f, bounds.maxOf { it.bottom } + 24f)
    }

    /** Known blue decoded covers distinguish song pixels from the flat shelf background. */
    private fun coverPixels(file: File, region: Rect): Int {
        val image = ImageIO.read(file)
        var count = 0
        for (y in region.top.toInt().coerceAtLeast(0) until region.bottom.toInt().coerceAtMost(image.height)) {
            for (x in region.left.toInt().coerceAtLeast(0) until region.right.toInt().coerceAtMost(image.width)) {
                val rgb = image.getRGB(x, y)
                val red = rgb ushr 16 and 0xff
                val green = rgb ushr 8 and 0xff
                val blue = rgb and 0xff
                if (blue > green + 20 && green > red + 25) count++
            }
        }
        return count
    }

    private fun clickText(scene: ImageComposeScene, text: String) {
        fun containsText(node: SemanticsNode): Boolean = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text } || node.children.any(::containsText)
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && containsText(it) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun clickDescription(scene: ImageComposeScene, description: String) {
        clickAt(scene, descriptionNode(scene, description).boundsInRoot.center)
    }

    private fun searchField(scene: ImageComposeScene): SemanticsNode? = nodes(scene).firstOrNull {
        it.config.contains(SemanticsActions.SetText)
    }

    private fun focusedSearchField(scene: ImageComposeScene): SemanticsNode =
        assertNotNull(searchField(scene), "Pressing search must reveal an editable field").also {
            assertTrue(it.config.getOrElse(SemanticsProperties.Focused) { false }, "Opening search must focus the field")
        }

    private fun setSearchText(scene: ImageComposeScene, query: String) {
        val setText = assertNotNull(focusedSearchField(scene).config[SemanticsActions.SetText].action)
        assertTrue(setText(AnnotatedString(query)))
    }

    private fun descriptionNode(scene: ImageComposeScene, description: String): SemanticsNode = nodes(scene).first {
        it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(description)
    }

    private fun hasDescription(scene: ImageComposeScene, description: String): Boolean = nodes(scene).any {
        it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(description)
    }

    private fun clickAt(scene: ImageComposeScene, point: Offset, button: PointerButton = PointerButton.Primary) {
        scene.sendPointerEvent(PointerEventType.Press, point, button = button)
        scene.sendPointerEvent(PointerEventType.Release, point, button = button)
    }

    private fun textBounds(scene: ImageComposeScene, text: String): Rect = nodes(scene).first {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text }
    }.boundsInRoot

    private fun fullSongHeading(scene: ImageComposeScene): SemanticsNode = nodes(scene).first {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == "全部歌曲" } &&
            it.boundsInRoot.height > 28f
    }

    private fun shelfIsMoving(scene: ImageComposeScene): Boolean = nodes(scene).any {
        it.config.getOrElse(SemanticsProperties.StateDescription) { "" }.contains("切换中")
    }

    private fun assertSlot(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.5f, "The completed page must occupy the original horizontal slot")
        assertEquals(expected.top, actual.top, 0.5f, "The completed page must occupy the original row")
    }

    /** Exercise real graphicsLayer positions while driving a monotonic scene frame clock. */
    private fun recordShelfTransition(
        scene: ImageComposeScene,
        prefix: String,
        incoming: String,
        outgoing: String,
        slot: Rect,
        songRegion: Rect,
        forward: Boolean,
        onIntermediate: () -> Unit = {},
    ) {
        var sawRunning = false
        var sawIntermediate = false
        val coverCounts = ArrayList<Pair<Int, Int>>()
        val outgoingFrames = ArrayList<Int>()
        val intermediateFailures = ArrayList<Throwable>()
        val movingStates = ArrayList<String>()
        var completed = false
        for (frame in 1..120) {
            val file = File(animationOutput, "$prefix-${frame.toString().padStart(3, '0')}.png")
            renderFrame(scene, file)
            coverCounts += frame to coverPixels(file, songRegion)
            val running = shelfIsMoving(scene)
            val states = nodes(scene).map { it.config.getOrElse(SemanticsProperties.StateDescription) { "" } }
                .filter(String::isNotBlank)
            movingStates += "$frame\trunning=$running\toutgoing=${songNodes(scene).contains(outgoing)}\tstates=$states"
            File(animationOutput, "$prefix-semantics-${frame.toString().padStart(3, '0')}.txt").writeText(
                movingStates.last() + "\n" + semanticsTrace(scene))
            sawRunning = sawRunning || running
            if (running && songNodes(scene).contains(outgoing)) outgoingFrames += frame
            if (running && !sawIntermediate) {
                val bounds = nodes(scene).firstOrNull { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == incoming }
                }?.boundsInRoot
                val translated = bounds != null && if (forward) bounds.left > slot.left + 1f else bounds.left < slot.left - 1f
                if (frame >= 4 && bounds != null && bounds.width > 1f && translated) {
                    sawIntermediate = true
                    runCatching(onIntermediate).exceptionOrNull()?.let(intermediateFailures::add)
                }
            }
            if (sawRunning && !running) {
                completed = true
                break
            }
        }
        File(animationOutput, "$prefix-cover-pixels.txt").writeText(
            coverCounts.joinToString("\n", postfix = "\n") { (index, count) -> "$index\t$count" })
        File(animationOutput, "$prefix-moving-states.txt").writeText(movingStates.joinToString("\n", postfix = "\n"))
        assertTrue(completed, "The $prefix spring did not complete after two seconds of frame-clock time")
        assertTrue(coverCounts.all { it.second > 20 },
            "Every slide frame, including the first, must retain visible song covers; empty frames=${coverCounts.filter { it.second <= 20 }}")
        assertTrue(outgoingFrames.isEmpty(),
            "The outgoing page must be absent from accessibility and click targets; frames=$outgoingFrames; see $prefix-semantics-*.txt")
        assertTrue(sawIntermediate, "Expected a visible translated frame before $prefix completed")
        assertTrue(intermediateFailures.isEmpty(), "Intermediate interaction failed: ${intermediateFailures.map { it.message }}")
        renderFrame(scene)
    }

    /** Raw tree for lifecycle diagnosis; unlike nodes(), include children hidden by clearing. */
    private fun semanticsTrace(scene: ImageComposeScene): String = buildString {
        fun appendNode(node: SemanticsNode, parentId: Int?, depth: Int) {
            val config = node.config
            appendLine("${"  ".repeat(depth)}id=${node.id} parentId=$parentId clearing=${config.isClearingSemantics} " +
                "merging=${config.isMergingSemanticsOfDescendants} " +
                "text=${config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }} " +
                "state=${config.getOrElse(SemanticsProperties.StateDescription) { "" }} " +
                "disabled=${config.contains(SemanticsProperties.Disabled)} click=${config.contains(SemanticsActions.OnClick)} bounds=${node.boundsInRoot}")
            node.children.forEach { appendNode(it, node.id, depth + 1) }
        }
        scene.semanticsOwners.forEachIndexed { index, owner ->
            appendLine("owner=$index")
            appendNode(owner.rootSemanticsNode, null, 0)
        }
    }

    private fun renderFrames(scene: ImageComposeScene) {
        // At least 750ms of animation time, then continue until a stable frame.
        // Focused text cursors may keep invalidating, so they do not block the
        // bounded drain once every shelf transition has demonstrably finished.
        var idleFrames = 0
        repeat(120) { frame ->
            renderFrame(scene)
            idleFrames = if (!scene.hasInvalidations() && !shelfIsMoving(scene)) idleFrames + 1 else 0
            if (frame >= 44 && idleFrames >= 3) return
        }
        assertFalse(shelfIsMoving(scene), "A shelf animation was still running after draining scene frames")
    }

    private fun save(scene: ImageComposeScene, file: File) {
        renderFrame(scene, file)
    }

    private fun renderFrame(scene: ImageComposeScene, file: File? = null) {
        Snapshot.sendApplyNotifications()
        val time = (sceneTimes[scene] ?: System.nanoTime()) + 16_666_667L
        sceneTimes[scene] = time
        scene.render(time).use { image ->
            if (file != null) {
                file.parentFile?.mkdirs()
                image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
            }
        }
        // Artwork decoders and completion notifications use real background
        // workers; give them a small opportunity to publish without using wall
        // time as the animation clock.
        if ((time / 16_666_667L) % 8L == 0L) Thread.sleep(2)
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) +
            if (node.config.isClearingSemantics) emptyList() else node.children.flatMap(::descendants)
        descendants(owner.rootSemanticsNode)
    }
    private fun textNodes(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { text -> text.text } }
    private fun songNodes(scene: ImageComposeScene) = textNodes(scene).filter { it.startsWith("song-") }
    private fun deps(store: ArtworkStore) = ArtistScreenDeps(ArtistRepository(log), store, PlaybackBridge(log), NavigationBridge(log), ArtistSettings(null), ArtistProfileStore(null, log), log)
    private fun model(count: Int, blankIds: Boolean = false): ArtistPageModel {
        val tracks = (0 until count).map { ArtistTrack(if (blankIds) "" else "song-$it", "song-$it", "Artist", "album-${it / 10}", "Artist", "", "", 2026, it, 180000, false) }
        val albums = tracks.chunked(10).mapIndexed { i, rows -> ArtistAlbum("album-$i", "Artist", 2026, rows) }
        return ArtistPageModel("Artist", tracks, albums, emptyList(), null)
    }
    private fun png(): ByteArray = ByteArrayOutputStream().also { out ->
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 128) for (x in 0 until 128) image.setRGB(x, y, 0x3675AF)
        ImageIO.write(image, "png", out)
    }.toByteArray()
    private fun awaitImage(request: DemandArtworkCache.Request<Artwork>): Artwork {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (request.state.value == null && System.nanoTime() < deadline) Thread.sleep(1)
        return assertNotNull(request.state.value)
    }
}
