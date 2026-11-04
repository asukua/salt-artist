package dev.saltartist.am

import dev.saltartist.PluginLog
import dev.saltartist.artwork.appleArtworkCacheKey
import java.nio.file.Files
import java.net.URI
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistVisualPolicyTest {
    private val log = PluginLog(null)
    private val profile: String get() = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
    private val motion: String get() = checkNotNull(javaClass.getResource("/apple-music/artist-motion-sanitized.json")).readText()
    private fun visualHtml(country: String): String {
        val payload = motion.replace("/us/artist/", "/${country.lowercase()}/artist/")
            .replace("fullscreen-master", "${country.lowercase()}-fullscreen-master")
        return "<script id=\"serialized-server-data\">$payload</script>"
    }
    private val lookup = """{"results":[{"artistId":42,"artistName":"test","artistLinkUrl":"https://music.apple.com/us/artist/test/42"}]}"""
    private val chinese = ArtistLanguagePolicy(PreferredArtistLanguage.CHINESE, false)
    private val identity = ArtistIdentityPolicy(enabled = false)
    private val us = ArtistVisualPolicy("US", PreferredArtistLanguage.ENGLISH)
    private val jp = ArtistVisualPolicy("JP", PreferredArtistLanguage.JAPANESE)

    @Test fun heroLocaleIsIndependentOfBiographyAndSurvivesRestart() {
        val dir = Files.createTempDirectory("salt-visual-source").toFile()
        try {
            val calls = mutableListOf<String>()
            val catalog = AppleMusicCatalog(dir, log, { url ->
                calls += url
                when {
                    "itunes.apple.com" in url -> lookup
                    "/us/artist/" in url && "l=en-US" in url -> visualHtml("US")
                    "/jp/artist/" in url && "l=ja-JP" in url -> visualHtml("JP")
                    else -> profile
                }
            }, { _, _, _, _ -> null })
            val first = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = us))
            assertEquals("zh-Hans", first.details?.languageCode)
            assertTrue(first.details?.biography.orEmpty().contains("中文"))
            assertTrue(first.motion?.url.orEmpty().contains("us-fullscreen-master"))
            assertTrue(calls.any { "/us/artist/42?l=zh-Hans" in it })
            assertTrue(calls.any { "/us/artist/42?l=en-US" in it })
            calls.clear()
            val second = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = jp))
            assertEquals(first.details?.biography, second.details?.biography)
            assertTrue(second.motion?.url.orEmpty().contains("jp-fullscreen-master"))
            assertEquals(listOf("https://music.apple.com/jp/artist/42?l=ja-JP"), calls)
            assertNotEquals(catalog.heroFile("test", identity, us), catalog.heroFile("test", identity, jp))
            assertNotEquals(appleArtworkCacheKey("test", chinese, identity, us), appleArtworkCacheKey("test", chinese, identity, jp))
            val reloaded = AppleMusicCatalog(dir, log, { error("cached data must not require network") })
            assertEquals(first.motion?.url, reloaded.cached("test", chinese, identity, us)?.motion?.url)
            assertEquals(second.motion?.url, reloaded.cached("test", chinese, identity, jp)?.motion?.url)
            assertEquals(first.details?.biography, reloaded.cached("test", chinese, identity, jp)?.details?.biography)
            assertTrue(reloaded.cachedIsFresh("test", chinese, identity, jp))
            // An explicit refresh removes all locale-specific records and files.
            val usFile = assertNotNull(reloaded.heroFile("test", identity, us)).apply { parentFile.mkdirs(); writeText("us") }
            val jpFile = assertNotNull(reloaded.heroFile("test", identity, jp)).apply { parentFile.mkdirs(); writeText("jp") }
            assertTrue(reloaded.forget("test"))
            assertFalse(usFile.exists())
            assertFalse(jpFile.exists())
            assertNull(reloaded.cached("test", chinese, identity, jp))
        } finally { dir.deleteRecursively() }
    }

    @Test fun unavailableSelectedVisualDoesNotBorrowAnotherLocaleOrInvalidateBiography() {
        val calls = mutableListOf<String>()
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                "l=en-US" in url -> visualHtml("US")
                "l=ja-JP" in url -> null
                else -> profile
            }
        }, { _, _, _, _ -> null })
        val first = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = us))
        assertNotNull(first.motion)
        val missing = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = jp))
        assertEquals(first.details?.biography, missing.details?.biography)
        assertNull(missing.motion)
        assertNull(missing.heroTemplate)
        assertFalse(catalog.cachedIsFresh("test", chinese, identity, jp))
        assertTrue(calls.any { "/jp/artist/42?l=ja-JP" in it })
    }

    @Test fun failedFirstRegionUsesSecondAndRetriesEarlierRegion() {
        val calls = mutableListOf<String>()
        var usAvailable = false
        val source = ArtistVisualPolicy("US,JP,CN", PreferredArtistLanguage.ENGLISH)
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                "l=zh-Hans" in url -> profile
                "/us/artist/" in url -> if (usAvailable) visualHtml("US") else null
                "/jp/artist/" in url -> visualHtml("JP")
                else -> error("later regions should not be requested after a motion hit")
            }
        }, { _, _, _, _ -> null })
        val fallback = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = source))
        assertTrue(fallback.motion?.url.orEmpty().contains("jp-fullscreen-master"))
        assertFalse(catalog.cachedIsFresh("test", chinese, identity, source), "temporary failures must remain retryable")
        assertEquals(listOf("us", "jp"), calls.filter { "l=en-US" in it }.map { URI(it).path.split('/')[1] })
        usAvailable = true
        calls.clear()
        val recovered = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = source))
        assertTrue(recovered.motion?.url.orEmpty().contains("us-fullscreen-master"))
        assertTrue(catalog.cachedIsFresh("test", chinese, identity, source))
        assertEquals(listOf("us"), calls.filter { "l=en-US" in it }.map { URI(it).path.split('/')[1] })
        assertEquals(fallback.details?.biography, recovered.details?.biography)
    }

    @Test fun temporaryFallbackSurvivesReloadButIsNotTreatedAsFresh() {
        val dir = Files.createTempDirectory("salt-visual-fallback").toFile()
        try {
            val source = ArtistVisualPolicy("US,JP", PreferredArtistLanguage.ENGLISH)
            val catalog = AppleMusicCatalog(dir, log, { url ->
                when {
                    "itunes.apple.com" in url -> lookup
                    "l=zh-Hans" in url -> profile
                    "/us/artist/" in url -> null
                    "/jp/artist/" in url -> visualHtml("JP")
                    else -> null
                }
            }, { _, _, _, _ -> null })
            val first = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = source))
            val reloaded = AppleMusicCatalog(dir, log, { error("cache inspection must be offline") })
            assertEquals(first.motion?.url, reloaded.cached("test", chinese, identity, source)?.motion?.url)
            assertFalse(reloaded.cachedIsFresh("test", chinese, identity, source))
            assertNull(reloaded.cached("test", chinese, identity, ArtistVisualPolicy("JP,US"))?.motion)
            assertNotEquals(reloaded.heroFile("test", identity, source),
                reloaded.heroFile("test", identity, ArtistVisualPolicy("JP,US")))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aLaterMotionIsPreferredOverAnEarlierStaticImage() {
        val calls = mutableListOf<String>()
        val source = ArtistVisualPolicy("US,JP,CN", PreferredArtistLanguage.ENGLISH)
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                "l=zh-Hans" in url -> profile
                "/us/artist/" in url -> visualHtml("US").replace("\"video\":", "\"ignoredVideo\":")
                "/jp/artist/" in url -> visualHtml("JP")
                else -> error("must stop after finding the first available motion")
            }
        }, { _, _, _, _ -> null })
        val result = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = source))
        assertTrue(result.motion?.url.orEmpty().contains("jp-fullscreen-master"))
        assertTrue(catalog.cachedIsFresh("test", chinese, identity, source))
        assertEquals(listOf("us", "jp"), calls.filter { "l=en-US" in it }.map { URI(it).path.split('/')[1] })
    }

    @Test fun whenNoRegionHasMotionTheFirstStillIsUsed() {
        val source = ArtistVisualPolicy("US,JP", PreferredArtistLanguage.ENGLISH)
        val catalog = AppleMusicCatalog(null, log, { url ->
            when {
                "itunes.apple.com" in url -> lookup
                "l=zh-Hans" in url -> profile
                "/us/artist/" in url -> visualHtml("US").replace("fullscreen-poster", "us-poster").replace("\"video\":", "\"ignoredVideo\":")
                "/jp/artist/" in url -> visualHtml("JP").replace("fullscreen-poster", "jp-poster").replace("\"video\":", "\"ignoredVideo\":")
                else -> null
            }
        }, { _, _, _, _ -> null })
        val result = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = source))
        assertNull(result.motion)
        assertTrue(result.heroTemplate.orEmpty().contains("us-poster"))
        assertTrue(catalog.cachedIsFresh("test", chinese, identity, source))
    }

    @Test fun aSingleExplicitRegionDoesNotTryOthers() {
        val source = ArtistVisualPolicy("US", PreferredArtistLanguage.ENGLISH)
        val calls = mutableListOf<String>()
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                "l=zh-Hans" in url -> profile
                else -> null
            }
        }, { _, _, _, _ -> null })
        val result = assertNotNull(catalog.resolve("test", chinese, identity = identity, visual = source))
        assertNull(result.heroTemplate)
        assertEquals(listOf("us"), calls.filter { "l=en-US" in it }.map { URI(it).path.split('/')[1] })
    }

    @Test fun aPreviousVisualForAnotherArtistCannotSupplyTheNewMatch() {
        val dir = Files.createTempDirectory("salt-visual-identity").toFile()
        try {
            val now = System.currentTimeMillis()
            val base = identity.artistCacheKey("test")
            val profileRecord = AmArtist("42", "old", "bb", true, null, "bb", now,
                details = AmArtistDetails(biography = "这是一段用于测试身份切换的中文简介。", languageCode = "zh-Hans"),
                detailsFetchedAt = now, motionCheckedAt = now)
            val wrongVisual = profileRecord.copy(artistId = "99", heroTemplate = "wrong", details = null)
            dir.resolve("apple-music-cache.tsv").writeText(
                AppleMusicCache.encode("$base|zh-Hans", profileRecord) + "\n" +
                    AppleMusicCache.encode("$base|visual:${us.token}", wrongVisual) + "\n")
            val catalog = AppleMusicCatalog(dir, log)
            assertNull(catalog.cached("test", chinese, identity, us)?.heroTemplate)
            assertFalse(catalog.cachedIsFresh("test", chinese, identity, us))
        } finally { dir.deleteRecursively() }
    }

    @Test fun realRoddyRicchLocaleVariantsWhenExplicitlyEnabled() {
        if (System.getenv("SALT_ARTIST_LIVE_TEST") != "1") return
        val catalog = AppleMusicCatalog(null, log, { url ->
            runCatching {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            }.getOrNull()
        }, { _, _, _, _ -> null })
        val binding = ArtistIdentityPolicy(overrides = "Roddy Ricch=1212442072", storefronts = "US")
        val english = assertNotNull(catalog.resolve("Roddy Ricch", chinese, identity = binding, visual = us))
        val chineseVisual = ArtistVisualPolicy("US", PreferredArtistLanguage.CHINESE)
        val chineseHero = assertNotNull(catalog.resolve("Roddy Ricch", chinese, identity = binding, visual = chineseVisual))
        assertEquals("1212442072", english.artistId)
        assertNotNull(english.motion)
        assertNotNull(chineseHero.motion)
        assertNotEquals(english.motion.url, chineseHero.motion.url)
        assertEquals(english.details?.biography, chineseHero.details?.biography)
    }

    @Test fun requestedSourceIsValidatedAndInvalidStorefrontFallsBackToUs() {
        assertEquals("JP", ArtistVisualPolicy(" jp ", PreferredArtistLanguage.CHINESE).country)
        assertEquals("US", ArtistVisualPolicy("../../host").country)
        assertEquals("US", ArtistVisualPolicy("ZZ").country)
        assertEquals(listOf("US", "JP", "CN"), ArtistVisualPolicy().countries)
        assertEquals("US-JP-CN-en-US", ArtistVisualPolicy().token)
        assertEquals("US-en-US", ArtistVisualPolicy("US").token)
        assertEquals(listOf("JP", "CN", "US"), ArtistVisualPolicy(" jp, CN； US,JP ").countries)
        assertEquals(listOf("US", "JP", "CN"), ArtistVisualPolicy("ZZ,??").countries)
        assertEquals(8, ArtistVisualPolicy("US,JP,CN,GB,FR,DE,KR,IN,AU").countries.size)
        assertNotEquals(ArtistVisualPolicy("US,JP").token, ArtistVisualPolicy("JP,US").token)
    }
}
