@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package dev.saltartist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
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
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ArtistArtworkRuntimeTest {
    private val output = File(System.getProperty("artwork.verification.dir")).apply { mkdirs() }
    private val log = PluginLog(null)

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

    @Test fun expandedAndSearchedLayoutsMatch031AtNarrowMediumAndWideWidths() {
        val model = model(36, blankIds = true)
        val deps = deps(ArtworkStore(null, log))
        val report = StringBuilder("Actual Compose CPU scene vs 0.3.1; density=1; placeholders isolate layout changes.\n")
        try {
            for (width in listOf(500, 1000, 1400)) for (mode in listOf("preview", "expanded", "search")) {
                val legacy = render(model, deps, width, mode, legacy = true)
                val current = render(model, deps, width, mode, legacy = false)
                val oldImage = ImageIO.read(legacy)
                val newImage = ImageIO.read(current)
                var different = 0
                for (y in 0 until oldImage.height) for (x in 0 until width) {
                    if (oldImage.getRGB(x, y) != newImage.getRGB(x, y)) different++
                }
                assertEquals(0, different, "Catalogue pixels changed at width=$width mode=$mode; see $output")
                report.append("width=$width mode=$mode differingPixels=$different\n")
            }
            File(output, "layout-comparison.txt").writeText(report.toString())
        } finally { deps.artwork.shutdown(); deps.settings.close() }
    }

    @Test fun actualLazySceneComposesOnlyVisibleSongsAndAlbumsDuringLargeLibraryScrolling() {
        val calls = AtomicInteger()
        val store = ArtworkStore(null, log)
        val bytes = png()
        store.libraryCoverSource = { calls.incrementAndGet(); CompletableFuture.completedFuture(bytes) }
        val deps = deps(store)
        val model = model(5000)
        val state = LazyListState()
        val query = mutableStateOf("")
        val expanded = mutableStateOf(true)
        var plan: ArtistCatalogPlan? = null
        val scene = ImageComposeScene(1400, 850) {
            plan = rememberArtistCatalog(model, 1400.dp, true, query.value, expanded.value)
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().background(ArtworkPalette.Fallback.backdropDeep)) {
                artistCatalog(checkNotNull(plan), deps, ArtworkPalette.Fallback, "song-1", true, query.value, { query.value = it }, {}, {}, {}, {}, { expanded.value = !expanded.value })
            }
        }
        try {
            renderFrames(scene)
            assertTrue(state.layoutInfo.totalItemsCount > 1600)
            assertTrue(songNodes(scene).size < 100, "All 5000 songs must not be composed")
            assertTrue(calls.get() < 100)
            runBlocking { state.scrollToItem(600) }
            renderFrames(scene)
            assertTrue(songNodes(scene).any { it.startsWith("song-600") || it.startsWith("song-602") })
            assertTrue(calls.get() < 200)
            runBlocking { state.scrollToItem(state.layoutInfo.totalItemsCount - 2) }
            renderFrames(scene)
            assertTrue(textNodes(scene).any { it.startsWith("album-") })
            runBlocking { state.scrollToItem(0) }
            expanded.value = false
            renderFrames(scene)
            assertTrue(checkNotNull(plan).songs.remainingRows == 0)
            query.value = "song-4999"
            renderFrames(scene)
            assertTrue(songNodes(scene).contains("song-4999"))
            assertTrue(calls.get() < 250)
            save(scene, File(output, "large-library-search.png"))
            File(output, "lazy-scene.txt").writeText("Actual Compose CPU scene: 5000 songs; 600-row jump, album section, return, collapse and search verified. Host cover fetches=${calls.get()}; visible song nodes=${songNodes(scene).size}.\n")
        } finally { scene.close(); store.shutdown(); deps.settings.close() }
    }

    private fun render(model: ArtistPageModel, deps: ArtistScreenDeps, width: Int, mode: String, legacy: Boolean): File {
        val expanded = mutableStateOf(false)
        val search = mode == "search"
        val query = if (search) "song" else ""
        val scene = ImageComposeScene(width, 900) {
            val plan = rememberArtistCatalog(model, width.dp, search, query, expanded.value)
            LazyColumn(Modifier.fillMaxSize().background(ArtworkPalette.Fallback.backdropDeep)) {
                item { Spacer(Modifier.height(40.dp)) }
                if (legacy) item {
                    LegacyArtistCatalog(model, deps, ArtworkPalette.Fallback, "", search, query, {}, {}, {}, {}, {})
                } else artistCatalog(plan, deps, ArtworkPalette.Fallback, "", search, query, {}, {}, {}, {}, {}, { expanded.value = !expanded.value })
            }
        }
        return File(output, "${if (legacy) "031" else "032"}-$width-$mode.png").also { file ->
            try {
                renderFrames(scene)
                if (mode == "expanded") {
                    val button = nodes(scene).first { node ->
                        node.config.contains(SemanticsActions.OnClick) && node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "显示全部" }
                    }
                    assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                    renderFrames(scene)
                }
                save(scene, file)
            } finally { scene.close() }
        }
    }

    private fun renderFrames(scene: ImageComposeScene) {
        repeat(8) { Snapshot.sendApplyNotifications(); scene.render(System.nanoTime()).close(); Thread.sleep(5) }
    }
    private fun save(scene: ImageComposeScene, file: File) {
        scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) } }
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
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
