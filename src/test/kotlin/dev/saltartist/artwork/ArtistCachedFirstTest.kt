package dev.saltartist.artwork

import dev.saltartist.PluginLog
import dev.saltartist.am.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArtistCachedFirstTest {
    @Test
    fun savedBiographyCountsAsCachedWhileUnavailableVisualRegionsStillNeedRefresh() {
        val directory = Files.createTempDirectory("salt-artist-download-fallback").toFile()
        val policy = ArtistLanguagePolicy(showOtherLanguages = false)
        val identity = ArtistIdentityPolicy(enabled = false)
        val visual = ArtistVisualPolicy()
        val now = System.currentTimeMillis()
        val profile = AmArtist("42", null, "bb", false, null, "bb", now,
            details = AmArtistDetails(biography = "这是一段已经下载并保存的中文测试简介，地区请求失败不会改变缓存结果。", languageCode = "zh-Hans"),
            detailsFetchedAt = now, motionCheckedAt = now)
        directory.resolve("apple-music-cache.tsv").writeText(
            AppleMusicCache.encode(identity.artistCacheKey("Artist") + "|zh-Hans", profile) + "\n" +
                AppleMusicCache.encode(identity.artistCacheKey("Artist") + "|visual:${visual.token}",
                    profile.copy(details = null, motionCheckedAt = 0L)))
        val catalog = AppleMusicCatalog(directory, PluginLog(null), { null })
        val store = ArtworkStore(directory, PluginLog(null), catalog)
        try {
            assertFalse(catalog.cachedIsFresh("Artist", policy, identity, visual))
            assertEquals(ArtistDownloadResult.COMPLETE, store.prefetchArtist("Artist", policy, identity, visual).get(3, TimeUnit.SECONDS))
            assertFalse(catalog.cachedIsFresh("Artist", policy, identity, visual))
            assertEquals(profile.details?.biography, catalog.cached("Artist", policy, identity, visual)?.details?.biography)
        } finally { store.shutdown(); directory.deleteRecursively() }
    }

    @Test
    fun anIdWithoutUsableContentIsNotCountedAsCached() {
        val directory = Files.createTempDirectory("salt-artist-download-empty").toFile()
        val policy = ArtistLanguagePolicy(showOtherLanguages = false)
        val identity = ArtistIdentityPolicy(enabled = false)
        val visual = ArtistVisualPolicy()
        val now = System.currentTimeMillis()
        val empty = AmArtist("42", null, "bb", false, null, "bb", now, detailsFetchedAt = now, motionCheckedAt = now)
        directory.resolve("apple-music-cache.tsv").writeText(
            AppleMusicCache.encode(identity.artistCacheKey("Artist") + "|zh-Hans", empty) + "\n" +
                AppleMusicCache.encode(identity.artistCacheKey("Artist") + "|visual:${visual.token}", empty))
        val catalog = AppleMusicCatalog(directory, PluginLog(null), { error("Fresh records must stay offline") })
        val store = ArtworkStore(directory, PluginLog(null), catalog)
        try {
            assertEquals(ArtistDownloadResult.RETRY, store.prefetchArtist("Artist", policy, identity, visual).get(3, TimeUnit.SECONDS))
        } finally { store.shutdown(); directory.deleteRecursively() }
    }

    @Test
    fun cachedMetadataIsReusedAndAnUnwritableCacheIsReportedForRetry() {
        val directory = Files.createTempDirectory("salt-artist-prefetch-persistence").toFile()
        val policy = ArtistLanguagePolicy(showOtherLanguages = false)
        val identity = ArtistIdentityPolicy(enabled = false)
        val now = System.currentTimeMillis()
        val meta = AmArtist("42", null, "bb", false, null, "bb", now, detailsFetchedAt = now, motionCheckedAt = now,
            details = AmArtistDetails(biography = "这是一段已经下载并保存的中文测试简介，用来验证持久化失败时的状态。", languageCode = "zh-Hans"))
        val file = directory.resolve("apple-music-cache.tsv")
        val visual = ArtistVisualPolicy()
        file.writeText(AppleMusicCache.encode(identity.artistCacheKey("Artist") + "|zh-Hans", meta) + "\n" +
            AppleMusicCache.encode(identity.artistCacheKey("Artist") + "|visual:${visual.token}", meta))
        val catalog = AppleMusicCatalog(directory, PluginLog(null), { error("Fresh cache must not make network requests") })
        val store = ArtworkStore(directory, PluginLog(null), catalog)
        try {
            assertEquals(ArtistDownloadResult.COMPLETE, store.prefetchArtist("Artist", policy, identity).get(3, TimeUnit.SECONDS))
            assertTrue(file.delete())
            assertTrue(file.mkdir()) // Simulate a destination that cannot be opened as a cache file.
            assertEquals(ArtistDownloadResult.RETRY, store.prefetchArtist("Artist", policy, identity).get(3, TimeUnit.SECONDS))
        } finally {
            store.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aSlowOfflineRefreshDoesNotHideTheDiskBiography() {
        val directory = Files.createTempDirectory("salt-artist-cached-first").toFile()
        val requestBegan = CountDownLatch(1)
        val releaseNetwork = CountDownLatch(1)
        val policy = ArtistLanguagePolicy(showOtherLanguages = false)
        val identity = ArtistIdentityPolicy(enabled = false)
        val twoDaysAgo = System.currentTimeMillis() - 2 * AppleMusicCatalog.CACHE_TTL_MS
        val meta = AmArtist("42", null, "bb", false, null, "bb", twoDaysAgo,
            detailsFetchedAt = twoDaysAgo, motionCheckedAt = twoDaysAgo,
            details = AmArtistDetails(biography = "这是一段已经下载的本地测试简介，断网时仍应立即展示。", languageCode = "zh-Hans"))
        directory.resolve("apple-music-cache.tsv").writeText(
            AppleMusicCache.encode(identity.artistCacheKey("Artist") + "|zh-Hans", meta) + "\n")
        val catalog = AppleMusicCatalog(directory, PluginLog(null), {
            requestBegan.countDown()
            check(releaseNetwork.await(3, TimeUnit.SECONDS))
            null
        }, { _, _, _, _ -> null })
        val store = ArtworkStore(directory, PluginLog(null), catalog)
        try {
            store.appleArt("Artist", policy, identity)
            assertTrue(requestBegan.await(3, TimeUnit.SECONDS))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var displayed = store.appleArt("Artist", policy, identity)
            while (displayed == null && System.nanoTime() < until) {
                Thread.sleep(5)
                displayed = store.appleArt("Artist", policy, identity)
            }
            assertEquals(meta.details?.biography, displayed?.details?.biography)
            assertFalse(catalog.isFresh("Artist", policy, identity))
        } finally {
            releaseNetwork.countDown()
            store.shutdown()
            directory.deleteRecursively()
        }
    }
}
