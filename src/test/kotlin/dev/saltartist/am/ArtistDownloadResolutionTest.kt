package dev.saltartist.am

import dev.saltartist.PluginLog
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistDownloadResolutionTest {
    private val log = PluginLog(null)
    private val identity = ArtistIdentityPolicy(enabled = false)
    private val policy = ArtistLanguagePolicy(storefronts = "US,JP,CN")
    private val visual = ArtistVisualPolicy("US,JP,CN", automaticLanguage = true)
    private val lookup = """{"results":[{"artistId":111051,"artistName":"Eminem","artistLinkUrl":"https://music.apple.com/us/artist/eminem/111051"}]}"""
    private val empty = """{"resultCount":0,"results":[]}"""
    private val noProfile: (String, String?, PluginLog, PreferredArtistLanguage) -> AmArtistDetails? = { _, _, _, _ -> null }

    @Test fun aConfirmedIdWithUnavailableMetadataIsRetryableRatherThanUnmatched() {
        val catalog = AppleMusicCatalog(null, log, { url -> if ("itunes.apple.com" in url) lookup else null }, noProfile)
        val result = catalog.resolveForDownload("Eminem", policy, identity, visual)
        assertNull(result.artist)
        assertEquals(DownloadResolutionStatus.RETRY, result.status)
    }

    @Test fun failedSearchesAreRetryableAndTheirNegativeCachePreservesTheReason() {
        var calls = 0
        val resolver = ArtistIdentityResolver({ calls++; null }, log)
        repeat(2) {
            val result = resolver.resolveResult("Eminem", identity)
            assertNull(result.artistId)
            assertTrue(result.retryable)
        }
        assertEquals(3, calls)
        val catalog = AppleMusicCatalog(null, log, { null }, noProfile)
        assertEquals(DownloadResolutionStatus.RETRY, catalog.resolveForDownload("Eminem", policy, identity, visual).status)
    }

    @Test fun successfulEmptySearchResponsesAreAnAuthoritativeNoMatch() {
        val catalog = AppleMusicCatalog(null, log, { empty }, noProfile)
        val result = catalog.resolveForDownload("A fictional nonexistent test artist", policy, identity, visual)
        assertNull(result.artist)
        assertEquals(DownloadResolutionStatus.NOT_FOUND, result.status)
    }

    @Test fun malformedSearchResponsesDoNotBecomeAuthoritativeNoMatch() {
        val resolver = ArtistIdentityResolver({ "<html>Temporarily unavailable</html>" }, log)
        assertTrue(resolver.resolveResult("Eminem", identity).retryable)
    }

    @Test fun oneFailedRegionAndOtherEmptyRegionsStillRequireRetry() {
        val resolver = ArtistIdentityResolver({ url -> if ("country=US" in url) null else empty }, log)
        val result = resolver.resolveResult("A fictional test artist", identity)
        assertNull(result.artistId)
        assertTrue(result.retryable)
    }

    @Test fun aLaterExactMatchRemainsSuccessfulAfterAnEarlierRegionalFailure() {
        val resolver = ArtistIdentityResolver({ url -> if ("country=US" in url) null else lookup }, log)
        val result = resolver.resolveResult("Eminem", identity)
        assertEquals("111051", result.artistId)
        assertFalse(result.retryable)
    }

    @Test fun aCachedFailedLookupCannotTurnAnotherExplicitBindingIntoNoMatch() {
        var calls = 0
        val resolver = ArtistIdentityResolver({ calls++; null }, log)
        val explicit = ArtistIdentityPolicy(overrides = "First Test Name=111051;Second Test Name=111051")
        assertTrue(resolver.resolveResult("First Test Name", explicit).retryable)
        val initialCalls = calls
        assertTrue(resolver.resolveResult("Second Test Name", explicit).retryable)
        assertEquals(initialCalls, calls)
    }

    @Test fun aParsedImageOnlyArtistIsFoundEvenWithoutBiographyOrSelectedHero() {
        val fixture = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
            .replace("42", "111051")
            .replace("这是一段为解析测试创作的中文介绍。\\n\\n该艺人的资料由公开页面提供，缺失事实不作补全。", "")
            .replace("An English fallback that must not override the Chinese header.", "")
        val catalog = AppleMusicCatalog(null, log, { url -> when {
            "itunes.apple.com" in url -> lookup
            URI(url).path.startsWith("/cn/") -> fixture
            else -> null
        } }, noProfile)
        val result = catalog.resolveForDownload("Eminem", policy, identity, visual)
        assertEquals(DownloadResolutionStatus.FOUND, result.status)
        assertEquals("111051", result.artist?.artistId)
        assertNull(result.artist?.details?.biography)
        assertNotNull(result.artist?.avatarTemplate)
        assertTrue(assertNotNull(result.artist).hasDownloadContent)
        assertFalse(catalog.cachedIsFresh("Eminem", policy, identity, visual))
    }

    @Test fun visualOnlySelectionsHaveDownloadContentEvenWhileRefreshIsOutstanding() {
        val empty = AmArtist("111051", null, "bb", false, null, "bb", 1L)
        val selections = listOf(
            empty.copy(heroTemplate = "https://is1-ssl.mzstatic.com/image/thumb/test/hero/{w}x{h}{c}.{f}"),
            empty.copy(avatarTemplate = "https://is1-ssl.mzstatic.com/image/thumb/test/avatar/{w}x{h}{c}.{f}"),
            empty.copy(logoTemplate = "https://is1-ssl.mzstatic.com/image/thumb/test/logo/{w}x{h}{c}.{f}"),
        )
        selections.forEach { selected ->
            assertNull(selected.details)
            assertEquals(0L, selected.motionCheckedAt)
            assertTrue(selected.hasDownloadContent)
        }
    }

    @Test fun biographyOnlyAndFactsOnlySelectionsCanBeSavedWithoutImages() {
        val empty = AmArtist("111051", null, "bb", false, null, "bb", 1L)
        assertTrue(empty.copy(details = AmArtistDetails(biography = "A biography without artwork.")).hasDownloadContent)
        assertTrue(empty.copy(details = AmArtistDetails(origin = "Detroit", genres = listOf("Hip-Hop/Rap"))).hasDownloadContent)
        assertTrue(empty.copy(details = AmArtistDetails(genres = listOf(" ", "Rock"))).hasDownloadContent)
    }

    @Test fun aDynamicMovieIsDownloadContentEvenWhenStaticArtworkIsMissing() {
        val movie = AmArtistMotion("https://mvod.itunes.apple.com/artist/movie.mp4", AmMotionKind.MP4)
        assertTrue(AmArtist("111051", null, "bb", false, null, "bb", 1L, motion = movie).hasDownloadContent)
    }

    @Test fun identityAndBlankOptionalMetadataDoNotClaimAnythingWasDownloaded() {
        val empty = AmArtist("111051", null, "bb", false, null, "bb", 1L)
        assertFalse(empty.hasDownloadContent)
        assertFalse(empty.copy(heroTemplate = " ", avatarTemplate = "\n", logoTemplate = "").hasDownloadContent)
        assertFalse(empty.copy(details = AmArtistDetails(biography = " ", born = "", formed = "\t", origin = "\n",
            genres = listOf("", " "), sourceUrl = "https://music.apple.com/us/artist/eminem/111051",
            languageCode = "en-US")).hasDownloadContent)
    }

    @Test fun staticChineseScriptHintsKeepSimplifiedAndTraditionalTextDistinct() {
        val simplified = "这是一段为回归测试创作的简体中文介绍。艺人的资料来自公开页面，没有新增的运行模块。"
        val traditional = "這是一段為回歸測試創作的繁體中文介紹。藝人的資料來自公開頁面，沒有新增的執行模組。"
        assertEquals(PreferredArtistLanguage.CHINESE,
            AppleMusicPageParser.localizedBiography(simplified, PreferredArtistLanguage.CHINESE, true)?.second)
        assertEquals(PreferredArtistLanguage.TRADITIONAL_CHINESE,
            AppleMusicPageParser.localizedBiography(traditional, PreferredArtistLanguage.CHINESE, true)?.second)
    }
}
