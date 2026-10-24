package dev.saltartist.artwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArtistMotionPlayerTest {
    @Test fun selectedResolutionUsesBestSdrStreamAndFallsBackWithinItsBounds() {
        val master = "https://mvod.itunes.apple.com/master.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:CODECS="hvc1.1.6",RESOLUTION=3840x2160,VIDEO-RANGE=PQ,BANDWIDTH=30000000
            hdr.m3u8
            #EXT-X-STREAM-INF:CODECS="hvc1.1.6",RESOLUTION=3840x2160,VIDEO-RANGE=SDR,BANDWIDTH=20000000
            uhd.m3u8
            #EXT-X-STREAM-INF:CODECS="hvc1.1.6",RESOLUTION=2560x1440,VIDEO-RANGE=SDR,BANDWIDTH=12000000
            qhd.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.640028",RESOLUTION=1920x1080,VIDEO-RANGE=SDR,BANDWIDTH=8000000
            hd.m3u8
        """.trimIndent()
        val qualities = dev.saltartist.settings.MotionResolution.entries
        for ((quality, name) in qualities.zip(listOf("hd", "qhd", "uhd")))
            assertEquals("https://mvod.itunes.apple.com/$name.m3u8", selectAppleMotionVariant(master, playlist, quality))
        assertEquals("https://mvod.itunes.apple.com/hd.m3u8", selectAppleMotionVariant(master,
            playlist.substringAfter("qhd.m3u8"), dev.saltartist.settings.MotionResolution.UHD))
    }

    private class Backend : MotionBackend {
        val calls = mutableListOf<List<String>>()
        var closed = 0
        val released = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var frame = false
        var rejected: String? = null
        override fun command(arguments: List<String>): Boolean {
            calls.add(arguments)
            if (arguments.firstOrNull() == "stop") stopped.countDown()
            return arguments.getOrNull(1) != rejected
        }
        override fun hasFrame(): Boolean = frame
        override fun close() { closed++; released.countDown() }
    }

    @Test
    fun disablesEveryAudioPathBeforeOpeningTheIndependentLoop() {
        val backend = Backend()
        MotionPlaybackController(backend).start("https://mvod.itunes.apple.com/hero.m3u8")
        val open = backend.calls.indexOfFirst { it.first() == "loadfile" }
        assertTrue(open > 0)
        val setup = backend.calls.take(open)
        for (guard in listOf("aid" to "no", "mute" to "yes", "volume" to "0", "loop-file" to "inf")) {
            assertTrue(listOf("set", guard.first, guard.second) in setup)
        }
        assertEquals(listOf("loadfile", "https://mvod.itunes.apple.com/hero.m3u8", "replace"), backend.calls.last())
    }

    @Test
    fun rejectedAudioGuardClosesWithoutEverLoadingTheStream() {
        val backend = Backend().apply { rejected = "aid" }
        val player = MotionPlaybackController(backend)
        assertFailsWith<IllegalStateException> { player.start("https://mvod.itunes.apple.com/hero.m3u8") }
        assertTrue(player.isClosed)
        assertFalse(backend.calls.any { it.first() == "loadfile" })
        assertEquals(1, backend.closed)
    }

    @Test
    fun readinessRequiresAFrameAndCloseStopsAndReleasesExactlyOnce() {
        val backend = Backend()
        val player = MotionPlaybackController(backend)
        player.start("https://mvod.itunes.apple.com/hero.m3u8")
        assertFalse(player.hasFrame())
        backend.frame = true
        assertTrue(player.hasFrame())
        player.close()
        player.close()
        assertFalse(player.hasFrame())
        assertEquals(listOf("stop"), backend.calls.last())
        assertEquals(1, backend.closed)
    }

    @Test
    fun shutdownStopsStreamingThenWaitsForNativeSurfaceDisposalBeforeDestroyingTheHandle() {
        val backend = Backend()
        val player = MotionPlaybackController(backend)
        assertTrue(player.attachSurface())
        player.closeAsync()
        assertTrue(player.isClosed)
        assertTrue(backend.stopped.await(2, TimeUnit.SECONDS))
        assertEquals(0, backend.closed)
        assertFalse(player.attachSurface())
        player.detachSurface()
        assertTrue(backend.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, backend.closed)
    }

    @Test
    fun waitingForSurfaceDisposalDoesNotBlockUiReadinessChecks() {
        val backend = Backend()
        val player = MotionPlaybackController(backend)
        val ui = Executors.newSingleThreadExecutor()
        assertTrue(player.attachSurface())
        player.closeAsync()
        try {
            assertTrue(backend.stopped.await(2, TimeUnit.SECONDS))
            assertFalse(ui.submit<Boolean> { player.hasFrame() }.get(1, TimeUnit.SECONDS))
            assertEquals(0, backend.closed)
        } finally {
            player.detachSurface()
            ui.shutdownNow()
        }
        assertTrue(backend.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, backend.closed)
    }

    @Test
    fun shutdownClosesEveryLivePlayerAndDoesNotCloseRemovedSessions() {
        ArtistMotionPlayers.begin()
        val first = Backend()
        val second = Backend()
        val removed = Backend()
        val removedPlayer = MotionPlaybackController(removed)
        ArtistMotionPlayers.add(MotionPlaybackController(first))
        ArtistMotionPlayers.add(MotionPlaybackController(second))
        ArtistMotionPlayers.add(removedPlayer)
        ArtistMotionPlayers.remove(removedPlayer)
        ArtistMotionPlayers.closeAll()
        ArtistMotionPlayers.closeAll()
        assertTrue(first.released.await(2, TimeUnit.SECONDS))
        assertTrue(second.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)
        assertEquals(0, removed.closed)
    }

    @Test
    fun acceptsOnlyAppleHttpsMovieResources() {
        assertTrue(isAppleMotionUrl("https://mvod.itunes.apple.com/a/movie.m3u8"))
        assertTrue(isAppleMotionUrl("https://video-ssl.itunes.apple.com/a/movie.mp4?token=a"))
        assertTrue(isAppleMotionUrl("https://is1-ssl.mzstatic.com/a/movie.mov"))
        for (url in listOf(
            "http://mvod.itunes.apple.com/movie.mp4",
            "https://itunes.apple.com.evil.example/movie.mp4",
            "https://evil.example@itunes.apple.com/movie.mp4",
            "https://mvod.itunes.apple.com:8080/movie.mp4",
            "https://mvod.itunes.apple.com/image.jpg",
            "file:///C:/movie.mp4",
        )) assertFalse(isAppleMotionUrl(url), url)
    }

    @Test
    fun hlsSelectionUsesOrdinaryH264SdrAndAvoidsPreviewHdrHevcAndHugeVariants() {
        val master = "https://mvod.itunes.apple.com/music/master.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-I-FRAME-STREAM-INF:CODECS="avc1.64001f",RESOLUTION=640x360,URI="preview.m3u8"
            #EXT-X-STREAM-INF:CODECS="hvc1.2.4",RESOLUTION=640x360,VIDEO-RANGE=SDR,BANDWIDTH=400000
            hevc.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.640028",RESOLUTION=1920x1080,VIDEO-RANGE=SDR,BANDWIDTH=5000000
            fullhd.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.640033",RESOLUTION=3840x2160,VIDEO-RANGE=SDR,BANDWIDTH=15000000
            ultra.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1024x576,VIDEO-RANGE=SDR,BANDWIDTH=2000000
            preferred.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.64001f",RESOLUTION=1280x720,VIDEO-RANGE=PQ,BANDWIDTH=1000000
            hdr.m3u8
        """.trimIndent()
        assertEquals("https://mvod.itunes.apple.com/music/fullhd.m3u8", selectAppleMotionVariant(master, playlist))
        assertEquals(null, selectAppleMotionVariant(master, "#EXTM3U\n#EXT-X-MAP:URI=\"track.mp4\"\n#EXTINF:3\ntrack.mp4"))
    }

    @Test
    fun hlsSelectionPrefersFullHdThenHigherBitrateRegardlessOfPlaylistOrder() {
        val master = "https://mvod.itunes.apple.com/music/master.m3u8"
        val variants = listOf(
            "#EXT-X-STREAM-INF:CODECS=\"avc1.640028\",RESOLUTION=1920x1080,BANDWIDTH=5000000\nhd-low.m3u8",
            "#EXT-X-STREAM-INF:CODECS=\"avc1.640028\",RESOLUTION=1920x1080,BANDWIDTH=8000000\nhd-high.m3u8",
            "#EXT-X-STREAM-INF:CODECS=\"avc1.64001f\",RESOLUTION=480x270,BANDWIDTH=300000\nsmall.m3u8",
            "#EXT-X-STREAM-INF:CODECS=\"avc1.640028\",RESOLUTION=1920x1080\nhd-unknown.m3u8",
        )
        for (ordered in listOf(variants, variants.reversed())) {
            assertEquals("https://mvod.itunes.apple.com/music/hd-high.m3u8",
                selectAppleMotionVariant(master, "#EXTM3U\n" + ordered.joinToString("\n")))
        }
    }

    @Test
    fun hlsSelectionUsesBestAvailableLowerResolutionWhenFullHdIsAbsent() {
        val master = "https://mvod.itunes.apple.com/music/master.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:CODECS="avc1.64001f",RESOLUTION=1280x720,BANDWIDTH=4000000
            best.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.64001f",RESOLUTION=480x270,BANDWIDTH=300000
            small.m3u8
        """.trimIndent()
        assertEquals("https://mvod.itunes.apple.com/music/best.m3u8", selectAppleMotionVariant(master, playlist))
    }

    @Test
    fun shutdownGenerationRejectsALateOpeningSession() {
        ArtistMotionPlayers.begin()
        val generation = ArtistMotionPlayers.generation()
        ArtistMotionPlayers.closeAll()
        val backend = Backend()
        val player = MotionPlaybackController(backend)
        assertFalse(ArtistMotionPlayers.add(player, generation))
        player.close()
        assertEquals(1, backend.closed)
    }

    @Test
    fun evenFreshGenerationCannotRegisterAfterStopUntilPluginStartsAgain() {
        ArtistMotionPlayers.begin()
        ArtistMotionPlayers.closeAll()
        val backend = Backend()
        val player = MotionPlaybackController(backend)
        assertFalse(ArtistMotionPlayers.add(player, ArtistMotionPlayers.generation()))
        ArtistMotionPlayers.begin()
        assertTrue(ArtistMotionPlayers.add(player, ArtistMotionPlayers.generation()))
        ArtistMotionPlayers.closeAll()
        assertTrue(backend.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, backend.closed)
    }

    private class MethodFixture {
        fun command(vararg values: String): Boolean = values.isNotEmpty()
        fun property(value: String): Boolean = value.isNotEmpty()
    }

    @Test
    fun choosesTheCommandByItsJvmDescriptorInsteadOfItsObfuscatedName() {
        val method = MethodFixture::class.java.methods.single(::isMotionCommandMethod)
        assertEquals("command", method.name)
        assertTrue(method.invoke(MethodFixture(), arrayOf("stop") as Any) as Boolean)
    }
}
