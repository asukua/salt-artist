package dev.saltartist.am

import dev.saltartist.PluginLog
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Incremental 0.3.1 behavior must retain the 0.3.0 scoped visual/profile caches. */
class AppleMusicRefreshTest {
    private val identity = ArtistIdentityPolicy(enabled = false)
    private val policy = ArtistLanguagePolicy(showOtherLanguages = false)
    private fun artist(time: Long) = AmArtist("42", null, "bb", false, null, "bb", time,
        detailsFetchedAt = time, motionCheckedAt = time)

    @Test
    fun dailyRefreshKeepsOfflineRecordsAndDoesNotFetchFreshMetadata() {
        val directory = Files.createTempDirectory("salt-artist-daily-refresh").toFile()
        try {
            val now = System.currentTimeMillis()
            val record = artist(now)
            val file = directory.resolve("apple-music-cache.tsv")
            val key = identity.artistCacheKey("Artist") + "|zh-Hans"
            file.writeText(AppleMusicCache.encode(key, record))
            val fresh = AppleMusicCatalog(directory, PluginLog(null), { error("Fresh cache must not fetch") })
            assertTrue(fresh.isFresh("Artist", policy, identity))
            assertEquals(record, fresh.resolve("Artist", policy, identity = identity))
            val previous = artist(now - AppleMusicCatalog.CACHE_TTL_MS - 1)
            file.writeText(AppleMusicCache.encode(key, previous))
            val requests = arrayListOf<String>()
            val offline = AppleMusicCatalog(directory, PluginLog(null), { url -> requests += url; null })
            assertFalse(offline.isFresh("Artist", policy, identity))
            assertEquals(previous, offline.resolve("Artist", policy, identity = identity))
            assertTrue(requests.isNotEmpty())
            assertFalse(offline.isFresh("Artist", policy, identity))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun clearingLoadedMetadataAlsoClearsItsVisualRecordAndDiskFiles() {
        val directory = Files.createTempDirectory("salt-artist-clear-loaded").toFile()
        try {
            val now = System.currentTimeMillis()
            val visual = ArtistVisualPolicy()
            val base = identity.artistCacheKey("Artist")
            directory.resolve("apple-music-cache.tsv").writeText(
                AppleMusicCache.encode("$base|zh-Hans", artist(now)) + "\n" +
                    AppleMusicCache.encode("$base|visual:${visual.token}", artist(now)))
            val catalog = AppleMusicCatalog(directory, PluginLog(null))
            assertNotNull(catalog.cached("Artist", policy, identity, visual))
            assertTrue(catalog.cachedIsFresh("Artist", policy, identity, visual))
            catalog.ensureImageDir()?.resolve("image.jpg")?.writeText("cached")
            assertEquals(2, catalog.clear())
            assertNull(catalog.cached("Artist", policy, identity, visual))
            assertFalse(catalog.cachedIsFresh("Artist", policy, identity, visual))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun aLateProfileCannotRepopulateAnExplicitlyClearedCache() {
        val directory = Files.createTempDirectory("salt-artist-clear-in-flight").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
        val catalog = AppleMusicCatalog(directory, PluginLog(null), { url ->
            if ("itunes.apple.com" in url) """{"results":[{"wrapperType":"artist","artistType":"Artist","artistId":42,"artistName":"test","artistLinkUrl":"https://music.apple.com/us/artist/test/42"}]}"""
            else fixture
        }, { _, _, _, _ -> entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); null })
        val pending = CompletableFuture.supplyAsync { catalog.resolve("test", policy) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            catalog.clear()
            release.countDown()
            assertNull(pending.get(3, TimeUnit.SECONDS))
            assertNull(catalog.cached("test"))
        } finally {
            release.countDown()
            pending.get(3, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }
}
