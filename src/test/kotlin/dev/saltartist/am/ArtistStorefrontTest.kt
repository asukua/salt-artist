package dev.saltartist.am

import dev.saltartist.PluginLog
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistStorefrontTest {
    private val log = PluginLog(null)
    private val fixture: String get() = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
    private val chinese = "这是一段为解析测试创作的中文介绍。\\n\\n该艺人的资料由公开页面提供，缺失事实不作补全。"
    private val english = "This original English biography exists only to verify that a requested language stays independent of the selected storefront."

    @Test
    fun storefrontsAreValidatedBoundedAndPartOfBothEnabledAndStrictCaches() {
        val policy = ArtistIdentityPolicy(storefronts = "us,XX,JP;cn，jp\nGB")
        assertEquals(listOf("US", "JP", "CN", "GB"), policy.storefrontOrder)
        assertEquals(8, ArtistIdentityPolicy(storefronts = "US,JP,CN,TW,HK,GB,DE,FR,CA,AU").storefrontOrder.size)
        assertEquals(listOf("US", "JP", "CN"), ArtistIdentityPolicy(storefronts = "invalid,ZZ,123").storefrontOrder)
        assertEquals(policy.cacheToken, ArtistIdentityPolicy(storefronts = "US,JP,CN,GB").cacheToken)
        assertNotEquals(policy.cacheToken, ArtistIdentityPolicy(storefronts = "CN,US,JP,GB").cacheToken)
        assertNotEquals(ArtistIdentityPolicy(false, storefronts = "US").cacheToken, ArtistIdentityPolicy(false, storefronts = "CN").cacheToken)
    }

    @Test
    fun unicodeSpacingAndWidthAreNormalizedWithoutRemovingIdentityPunctuationOrAccents() {
        assertEquals(artistIdentityKey("Hatsune Miku"), artistIdentityKey("\ufeffＨＡＴＳＵＮＥ\u00a0\u200bＭｉｋｕ\u2060"))
        assertEquals(artistIdentityKey("Café"), artistIdentityKey("Cafe\u0301"))
        assertNotEquals(artistIdentityKey("Café"), artistIdentityKey("Cafe"))
        assertEquals(artistIdentityKey("AC/DC"), artistIdentityKey("ＡＣ／ＤＣ"))
        assertNotEquals(artistIdentityKey("AC/DC"), artistIdentityKey("AC DC"))
        assertNotEquals(artistIdentityKey("A & B"), artistIdentityKey("A"))
    }

    @Test
    fun aDefaultUsTransportFailurePrioritizesCnAndSkipsUsForFiveMinutes() {
        var now = 0L
        val health = ArtistStorefrontHealth { now }
        val requests = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            val country = country(url)
            requests += country
            if (country == "US") null else if (country == "CN") result("42", "Solo Artist") else "{\"results\":[]}"
        }, log, health)
        assertEquals("42", resolver.resolve("Solo Artist", ArtistIdentityPolicy()))
        assertEquals(listOf("US", "CN", "JP"), requests)
        requests.clear()
        assertEquals("42", resolver.resolve("Solo Artist", ArtistIdentityPolicy(overrides = "Other=99")))
        assertEquals(listOf("CN", "JP"), requests)
        now = 5L * 60 * 1000 + 1L
        requests.clear()
        assertEquals("42", resolver.resolve("Solo Artist", ArtistIdentityPolicy(overrides = "Other=98")))
        assertEquals(listOf("US", "CN", "JP"), requests)
    }

    @Test
    fun validEmptyUsResultsKeepDefaultOrderAndCustomOrdersIgnoreHealthAdaptation() {
        val health = ArtistStorefrontHealth { 1L }
        val requests = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            val region = country(url)
            requests += region
            if (region == "JP") result("42", "Solo Artist") else "{\"results\":[]}"
        }, log, health)
        assertEquals("42", resolver.resolve("Solo Artist", ArtistIdentityPolicy()))
        assertEquals(listOf("US", "JP", "CN"), requests)
        health.unavailable("US", listOf("US", "GB", "JP"))
        assertEquals(listOf("US", "GB", "JP"), health.attempts(listOf("US", "GB", "JP")).toList())
    }

    @Test
    fun manualNameSearchReturnsDeduplicatedSimilarCandidatesButDoesNotBindThem() {
        val requests = arrayListOf<String>()
        val catalog = AppleMusicCatalog(null, log, { url ->
            requests += url
            if (country(url) == "US") results(listOf("99" to "Allday Project", "42" to "Allday")) else result("42", "Allday")
        }, { _, _, _, _ -> error("Candidate search must not load profiles") })
        val policy = ArtistIdentityPolicy(storefronts = "US,JP")
        val candidates = catalog.searchCandidates("Allday", policy)
        assertEquals(listOf("42", "99"), candidates.map { it.id })
        assertTrue(candidates.all { it.avatarUrl == null && AppleMusicPageParser.artistIdOf(it.url) == it.id })
        assertTrue(requests.all { "/search?" in it })
        assertNull(catalog.cached("Allday", identity = policy))
        val count = requests.size
        assertEquals(candidates, catalog.searchCandidates("Allday", policy))
        assertEquals(count, requests.size)
    }

    @Test
    fun manualIdAndAppleLinksUseValidatedLookupWithRegionalRetry() {
        val requests = arrayListOf<String>()
        val catalog = AppleMusicCatalog(null, log, { url ->
            requests += url
            if (country(url) == "US") null else result("42", "Verified Artist")
        })
        assertEquals("42", catalog.searchCandidates("42", ArtistIdentityPolicy()).single().id)
        assertEquals(listOf("US", "CN"), requests.map(::country))
        assertEquals("42", catalog.searchCandidates("https://music.apple.com/jp/artist/test/42", ArtistIdentityPolicy()).single().id)
        assertEquals("42", catalog.searchCandidates("https://music.apple.com/jp/artist/${"artist-".repeat(60)}/42", ArtistIdentityPolicy()).single().id)
        val count = requests.size
        assertTrue(catalog.searchCandidates("https://music.apple.com/us/artist/test/42/999", ArtistIdentityPolicy()).isEmpty())
        assertEquals(count, requests.size)
        assertTrue(requests.all { "/lookup?" in it })
    }

    @Test
    fun avatarAndProfilePagesRetryConfiguredRegionsWithoutChangingTheRequestedLanguage() {
        val requests = arrayListOf<String>()
        val identity = ArtistIdentityPolicy(overrides = "Local Alias=42", storefronts = "TW,HK")
        val catalog = AppleMusicCatalog(null, log, { url ->
            requests += url
            when {
                "/lookup?" in url -> result("42", "Verified Artist")
                "/tw/artist/" in url -> null
                else -> fixture.replace(chinese, english)
            }
        }, { _, _, _, _ -> null })
        val resolved = assertNotNull(catalog.resolve("Local Alias", ArtistLanguagePolicy(PreferredArtistLanguage.ENGLISH, false), identity = identity))
        assertEquals(english, resolved.details?.biography)
        val pages = requests.filter { "music.apple.com/" in it && "/artist/" in it }
        assertEquals(listOf("tw", "hk"), pages.map { URI(it).path.split('/')[1] })
        assertTrue(pages.all { "l=en-US" in it })
        requests.clear()
        val avatarCatalog = AppleMusicCatalog(null, log, { url ->
            requests += url
            if ("/lookup?" in url) result("42", "Verified Artist") else if ("/tw/artist/" in url) null else fixture
        }, { _, _, _, _ -> error("Avatars must remain lightweight") })
        assertEquals("42", avatarCatalog.resolveAvatar("Local Alias", identity)?.artistId)
        assertTrue(requests.any { "/hk/artist/42?l=zh-Hans" in it })
        assertFalse(requests.any { "amp-api" in it || "/assets/" in it || "mzstatic.com" in it })
        assertNull(catalog.cached("Local Alias", identity = identity.copy(storefronts = "JP")))
    }

    @Test
    fun theVerifiedGorillazIdAvoidsAnObservedSameNameCollision() {
        val requests = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            requests += url
            if ("/lookup?" in url) result("567072", "Gorillaz")
            else results(listOf("567072" to "Gorillaz", "1529389870" to "Gorillaz"))
        }, log)
        assertEquals("567072", resolver.resolve("Gorillaz", ArtistIdentityPolicy()))
        assertTrue(requests.all { "/lookup?id=567072&" in it })
        val rejected = ArtistIdentityResolver({ result("567072", "Unrelated artist") }, log)
        assertNull(rejected.resolve("Gorillaz", ArtistIdentityPolicy()))
    }

    @Test
    fun aNewMotionVariantWinsItsOwnPosterAndOldMotionIsNotResurrected() {
        val static = AmArtist("42", "wide-static", "bb", true, null, "bb", 10L, motionCheckedAt = 10L)
        val motion = AmArtistMotion("https://example.invalid/new.m3u8", AmMotionKind.HLS, "motion-poster", "sr")
        val animated = static.copy(heroTemplate = "motion-poster", heroCrop = "sr", heroIsWide = false, motion = motion)
        val selected = AppleMusicCatalog.mergePages(listOf(static, animated), null, 10L)
        assertEquals(motion, selected.motion)
        assertEquals("motion-poster", selected.heroTemplate)
        val refreshed = AppleMusicCatalog.mergePages(listOf(static), selected.copy(detailsFetchedAt = 10L), 20L)
        assertNull(refreshed.motion)
        assertEquals(10L, refreshed.motionCheckedAt)
    }

    private fun country(url: String) = URI(url).rawQuery.split('&').first { it.startsWith("country=") }.substringAfter('=')
    private fun result(id: String, name: String) = results(listOf(id to name))
    private fun results(items: List<Pair<String, String>>) = "{\"results\":[" + items.joinToString(",") { (id, name) ->
        "{\"artistId\":$id,\"artistName\":\"$name\",\"artistLinkUrl\":\"https://music.apple.com/us/artist/test/$id\"}"
    } + "]}"
}
