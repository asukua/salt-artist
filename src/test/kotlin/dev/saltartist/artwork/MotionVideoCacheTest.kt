package dev.saltartist.artwork

import dev.saltartist.PluginLog
import dev.saltartist.settings.MotionResolution
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MotionVideoCacheTest {
    private val master = "https://mvod.itunes.apple.com/artist/master.m3u8"
    private val playlist = """
        #EXTM3U
        #EXT-X-STREAM-INF:CODECS="avc1.640028",RESOLUTION=1920x1080,BANDWIDTH=8000000
        hd.m3u8
    """.trimIndent()
    private val media = "#EXTM3U\n#EXT-X-MAP:URI=\"movie.mp4\",BYTERANGE=\"32@0\"\n#EXTINF:1\n#EXT-X-BYTERANGE:32@32\nmovie.mp4\n#EXT-X-ENDLIST"
    private fun movie() = ByteArray(64).apply { "ftyp".toByteArray().copyInto(this, 4) }

    @Test fun completedVideoReloadsWithoutNetworkAndQualityUsesSeparateCache() {
        val root = Files.createTempDirectory("motion-cache").toFile()
        val requests = AtomicInteger()
        val downloads = AtomicInteger()
        try {
            MotionVideoCache(root, PluginLog(null), { requests.incrementAndGet(); if (it == master) playlist else media },
                { _, destination -> downloads.incrementAndGet(); destination.writeBytes(movie()) }).use { cache ->
                val stream = cache.prepare(master, MotionResolution.FULL_HD)
                assertFalse(stream.cached)
                assertEquals(1, requests.get()) // Preparing never waits for a full movie download.
                assertNotNull(cache.warm(stream).get(3, TimeUnit.SECONDS))
                assertTrue(cache.prepare(master, MotionResolution.FULL_HD).cached)
            }
            MotionVideoCache(root, PluginLog(null), { error("Cache hit must not request a manifest") },
                { _, _ -> error("Cache hit must not download") }).use { cache ->
                val hit = cache.prepare(master, MotionResolution.FULL_HD)
                assertTrue(hit.cached)
                assertTrue(java.io.File(hit.location).isFile)
                assertFalse(cache.prepare(master, MotionResolution.QHD).cached)
                cache.invalidate(master, MotionResolution.FULL_HD)
                assertFalse(cache.prepare(master, MotionResolution.FULL_HD).cached)
            }
            assertEquals(1, downloads.get())
        } finally { root.deleteRecursively() }
    }

    @Test fun clearDuringDownloadRejectsLateFileAndConcurrentWarmIsDeduplicated() {
        val root = Files.createTempDirectory("motion-clear").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            MotionVideoCache(root, PluginLog(null), { if (it == master) playlist else media }, { _, file ->
                started.countDown(); check(release.await(3, TimeUnit.SECONDS)); file.writeBytes(movie())
            }).use { cache ->
                val stream = cache.prepare(master, MotionResolution.FULL_HD)
                val first = cache.warm(stream)
                assertTrue(started.await(3, TimeUnit.SECONDS))
                assertSame(first, cache.warm(stream))
                cache.clear(); release.countDown()
                assertNull(first.get(3, TimeUnit.SECONDS))
                assertFalse(cache.prepare(master, MotionResolution.FULL_HD).cached)
            }
        } finally { release.countDown(); root.deleteRecursively() }
    }

    @Test fun incompleteDownloadNeverBecomesALocalPlaybackSource() {
        val root = Files.createTempDirectory("motion-incomplete").toFile()
        try {
            MotionVideoCache(root, PluginLog(null), { if (it == master) playlist else media },
                { _, file -> file.writeBytes(ByteArray(64)) }).use { cache ->
                val source = cache.prepare(master, MotionResolution.FULL_HD)
                assertNull(cache.warm(source).get(3, TimeUnit.SECONDS))
                assertFalse(cache.prepare(master, MotionResolution.FULL_HD).cached)
                assertTrue(root.walk().none { it.extension == "mp4" })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun diskCacheKeepsAtMostEightMovies() {
        val root = Files.createTempDirectory("motion-limit").toFile()
        try {
            MotionVideoCache(root, PluginLog(null), { media }, { _, file -> file.writeBytes(movie()) }).use { cache ->
                for (index in 0..9) {
                    val source = cache.prepare("https://mvod.itunes.apple.com/artist/$index.mp4", MotionResolution.FULL_HD)
                    assertNotNull(cache.warm(source).get(3, TimeUnit.SECONDS))
                }
                assertEquals(8, root.walk().count { it.extension == "mp4" })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun cachesOnlySingleFileUnencryptedFiniteAppleMovies() {
        assertEquals("https://mvod.itunes.apple.com/artist/movie.mp4", singleFileMotionMovie(master, media))
        assertNull(singleFileMotionMovie(master, media.replace("#EXT-X-ENDLIST", "")))
        assertNull(singleFileMotionMovie(master, "#EXT-X-KEY:METHOD=AES-128\n$media"))
        assertNull(singleFileMotionMovie(master, media.replace("#EXT-X-ENDLIST", "other.mp4\n#EXT-X-ENDLIST")))
        assertNull(singleFileMotionMovie(master, media.replace("movie.mp4", "https://evil.example/movie.mp4")))
        assertNull(singleFileMotionMovie(master, media.replace("movie.mp4", "file:///movie.mp4")))
    }
}
