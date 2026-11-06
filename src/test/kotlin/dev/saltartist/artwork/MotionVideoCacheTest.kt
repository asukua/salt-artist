package dev.saltartist.artwork

import dev.saltartist.PluginLog
import dev.saltartist.settings.MotionResolution
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
                assertEquals(java.io.File(hit.location), cache.downloadForOffline(master, MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
                assertEquals(java.io.File(hit.location), cache.warm(hit).get(3, TimeUnit.SECONDS))
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
                assertSame(first, cache.downloadForOffline(master, MotionResolution.FULL_HD))
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

    @Test fun completedOfflineMoviesAreNotEvictedAfterEightArtists() {
        val root = Files.createTempDirectory("motion-limit").toFile()
        try {
            MotionVideoCache(root, PluginLog(null), { media }, { _, file -> file.writeBytes(movie()) }).use { cache ->
                for (index in 0..9) {
                    assertNotNull(cache.downloadForOffline("https://mvod.itunes.apple.com/artist/$index.mp4", MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
                }
                assertEquals(10, root.walk().count { it.extension == "mp4" })
                assertTrue(cache.prepare("https://mvod.itunes.apple.com/artist/0.mp4", MotionResolution.FULL_HD).cached)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun completedOfflineMoviesAreNotEvictedAboveTheFormerTotalSizeLimit() {
        val root = Files.createTempDirectory("motion-total-size").toFile()
        val size = 100L * 1024 * 1024
        try {
            MotionVideoCache(root, PluginLog(null), { media }, { _, file -> sparseMovie(file, size) }).use { cache ->
                val files = (0..5).map { index ->
                    assertNotNull(cache.downloadForOffline("https://mvod.itunes.apple.com/artist/$index.mp4", MotionResolution.UHD).get(3, TimeUnit.SECONDS))
                }
                assertTrue(files.all { it.isFile && it.length() == size })
                assertEquals(600L * 1024 * 1024, files.sumOf { it.length() })
                assertTrue(cache.prepare("https://mvod.itunes.apple.com/artist/0.mp4", MotionResolution.UHD).cached)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun offlineDownloadSelectsTheConfiguredResolutionAndCachesEachTierIndependently() {
        val root = Files.createTempDirectory("motion-offline-quality").toFile()
        val requested = arrayListOf<String>()
        val variants = playlist + "\n#EXT-X-STREAM-INF:CODECS=\"hvc1.2.4.L153\",RESOLUTION=3840x2160,BANDWIDTH=40000000\nuhd.m3u8"
        try {
            MotionVideoCache(root, PluginLog(null), { url -> when {
                url == master -> variants
                url.endsWith("uhd.m3u8") -> media.replace("movie.mp4", "uhd.mp4")
                else -> media.replace("movie.mp4", "hd.mp4")
            } }, { url, file -> requested += url; file.writeBytes(movie()) }).use { cache ->
                val hd = assertNotNull(cache.downloadForOffline(master, MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
                val uhd = assertNotNull(cache.downloadForOffline(master, MotionResolution.UHD).get(3, TimeUnit.SECONDS))
                assertNotEquals(hd, uhd)
                assertEquals(listOf("https://mvod.itunes.apple.com/artist/hd.mp4", "https://mvod.itunes.apple.com/artist/uhd.mp4"), requested)
                assertEquals(hd, cache.downloadForOffline(master, MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
                assertEquals(2, requested.size)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedOfflineDownloadsCanBeRetriedAndDoNotPublishPartialMovies() {
        val root = Files.createTempDirectory("motion-offline-retry").toFile()
        val downloads = AtomicInteger()
        try {
            MotionVideoCache(root, PluginLog(null), { if (it == master) playlist else media }, { _, file ->
                file.writeBytes(if (downloads.incrementAndGet() == 1) ByteArray(64) else movie())
            }).use { cache ->
                assertNull(cache.downloadForOffline(master, MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
                assertFalse(cache.prepare(master, MotionResolution.FULL_HD).cached)
                assertNotNull(cache.downloadForOffline(master, MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
                assertTrue(cache.prepare(master, MotionResolution.FULL_HD).cached)
                assertEquals(2, downloads.get())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun aNewRequestAfterClearDoesNotReuseTheOldDownloadOrItsManifest() {
        val root = Files.createTempDirectory("motion-clear-prepare").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manifests = AtomicInteger()
        val downloaded = arrayListOf<String>()
        try {
            MotionVideoCache(root, PluginLog(null), { url ->
                if (url == master) {
                    if (manifests.incrementAndGet() == 1) {
                        started.countDown(); check(release.await(3, TimeUnit.SECONDS))
                        playlist.replace("hd.m3u8", "old.m3u8")
                    } else playlist.replace("hd.m3u8", "new.m3u8")
                } else media.replace("movie.mp4", if (url.endsWith("new.m3u8")) "new.mp4" else "old.mp4")
            }, { url, file -> downloaded += url; file.writeBytes(movie()) }).use { cache ->
                val old = cache.downloadForOffline(master, MotionResolution.FULL_HD)
                assertTrue(started.await(3, TimeUnit.SECONDS))
                cache.clear()
                val fresh = cache.downloadForOffline(master, MotionResolution.FULL_HD)
                assertNotSame(old, fresh)
                release.countDown()
                assertNull(old.get(3, TimeUnit.SECONDS))
                assertNotNull(fresh.get(3, TimeUnit.SECONDS))
                assertEquals(2, manifests.get())
                assertEquals(listOf("https://mvod.itunes.apple.com/artist/new.mp4"), downloaded)
            }
        } finally { release.countDown(); root.deleteRecursively() }
    }

    @Test fun aPreparedPlaybackSourceCannotRefillTheCacheAfterClear() {
        val root = Files.createTempDirectory("motion-clear-source").toFile()
        val downloads = AtomicInteger()
        try {
            MotionVideoCache(root, PluginLog(null), { if (it == master) playlist else media }, { _, file ->
                downloads.incrementAndGet(); file.writeBytes(movie())
            }).use { cache ->
                val stale = cache.prepare(master, MotionResolution.FULL_HD)
                cache.clear()
                assertNull(cache.warm(stale).get(3, TimeUnit.SECONDS))
                assertEquals(0, downloads.get())
                assertTrue(root.walk().none { it.isFile })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun closeDuringPreparationRejectsLateStreamSidecarsAndFurtherDownloads() {
        val root = Files.createTempDirectory("motion-close-prepare").toFile()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val cache = MotionVideoCache(root, PluginLog(null), { url ->
            if (url == master) { started.countDown(); check(release.await(3, TimeUnit.SECONDS)); playlist } else media
        }, { _, _ -> error("A closed cache cannot download") })
        try {
            val pending = worker.submit<PreparedMotion> { cache.prepare(master, MotionResolution.FULL_HD) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            cache.close(); release.countDown()
            val source = pending.get(3, TimeUnit.SECONDS)
            assertNull(cache.warm(source).get(3, TimeUnit.SECONDS))
            assertNull(cache.downloadForOffline(master, MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
            assertTrue(root.walk().none { it.isFile })
        } finally { release.countDown(); cache.close(); worker.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun thePerMovieInputLimitStillRejectsOversizedDownloads() {
        val root = Files.createTempDirectory("motion-input-size").toFile()
        try {
            MotionVideoCache(root, PluginLog(null), { media }, { _, file -> sparseMovie(file, 128L * 1024 * 1024 + 1) }).use { cache ->
                assertNull(cache.downloadForOffline("https://mvod.itunes.apple.com/artist/large.mp4", MotionResolution.UHD).get(3, TimeUnit.SECONDS))
                assertFalse(cache.prepare("https://mvod.itunes.apple.com/artist/large.mp4", MotionResolution.UHD).cached)
                assertTrue(root.walk().none { it.extension == "mp4" })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun aPublishedMoviesCompletionCallbackCanReadAndClearTheCacheFromAnotherThread() {
        completionCallbackCanReadAndClear(cachedHit = false)
    }

    @Test fun aCacheHitsCompletionCallbackCanReadAndClearTheCacheFromAnotherThread() {
        completionCallbackCanReadAndClear(cachedHit = true)
    }

    private fun completionCallbackCanReadAndClear(cachedHit: Boolean) {
        val root = Files.createTempDirectory("motion-callback-lock").toFile()
        val target = "https://mvod.itunes.apple.com/artist/target.mp4"
        val blocker = if (cachedHit) "https://mvod.itunes.apple.com/artist/blocker.mp4" else target
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observer = Executors.newSingleThreadExecutor()
        try {
            MotionVideoCache(root, PluginLog(null), { media }, { url, file ->
                if (url == blocker) { started.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                file.writeBytes(movie())
            }).use { cache ->
                if (cachedHit) assertNotNull(cache.downloadForOffline(target, MotionResolution.FULL_HD).get(3, TimeUnit.SECONDS))
                val blocked = cache.downloadForOffline(blocker, MotionResolution.FULL_HD)
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val pending = if (cachedHit) cache.downloadForOffline(target, MotionResolution.FULL_HD) else blocked
                val callback = pending.thenApply { file ->
                    assertTrue(assertNotNull(file).isFile)
                    observer.submit<Boolean> {
                        val wasCached = cache.prepare(target, MotionResolution.FULL_HD).cached
                        cache.clear()
                        wasCached
                    }.get(2, TimeUnit.SECONDS)
                }
                release.countDown()
                assertTrue(callback.get(5, TimeUnit.SECONDS))
                assertTrue(root.walk().none { it.isFile })
            }
        } finally { release.countDown(); observer.shutdownNow(); root.deleteRecursively() }
    }

    private fun sparseMovie(file: java.io.File, size: Long) {
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { channel ->
            channel.write(ByteBuffer.wrap(movie()))
            channel.position(size - 1)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
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
