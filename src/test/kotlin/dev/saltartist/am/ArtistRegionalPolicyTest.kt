package dev.saltartist.am

import dev.saltartist.PluginLog
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistRegionalPolicyTest {
    private val identity = ArtistIdentityPolicy(enabled = false)
    private val log = PluginLog(null)
    private val chinese = "这是一段为解析测试创作的中文介绍。\\n\\n该艺人的资料由公开页面提供，缺失事实不作补全。"
    private val english = "This is an original English biography written only for regional policy tests and contains no copied editorial text."
    private val japanese = "これは地域ごとの言語設定と紹介文の選択を検証するために作成されたオリジナルの文章です。"
    private val traditional = "這是一段為測試創作的繁體中文介紹，藝人的資料來自公開頁面，缺少的事實不作補充。"
    private val fixture: String get() = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
    private val lookup = """{"results":[{"artistId":42,"artistName":"test","artistLinkUrl":"https://music.apple.com/us/artist/test/42"}]}"""
    private val noProfile: (String, String?, PluginLog, PreferredArtistLanguage) -> AmArtistDetails? = { _, _, _, _ -> null }
    private fun page(biography: String): String = fixture.replace(chinese, biography)
        .replace("An English fallback that must not override the Chinese header.", "")
    private fun policy(regions: String, chineseFirst: Boolean = true) =
        ArtistLanguagePolicy(storefronts = regions, preferChineseBiography = chineseFirst)
    private fun pageCalls(calls: List<String>) = calls.filter { URI(it).host == "music.apple.com" && "/artist/" in it }
    private fun pageUrl(country: String, language: String) = "https://music.apple.com/$country/artist/42?l=$language"

    @Test fun nativeLocalesAreExplicitAndAutomaticHeroKeysCannotReuseFixedLanguageKeys() {
        val expected = mapOf("US" to "en-US", "GB" to "en-GB", "JP" to "ja-JP", "CN" to "zh-Hans",
            "TW" to "zh-Hant-TW", "HK" to "zh-Hant-HK", "KR" to "ko-KR", "FR" to "fr-FR", "DE" to "de-DE")
        expected.forEach { (country, locale) -> assertEquals(locale, nativeArtistLocale(country).pageLanguageCode) }
        assertEquals("en-US", nativeArtistLocale("ZA").pageLanguageCode)
        val auto = ArtistVisualPolicy("US,JP", automaticLanguage = true)
        assertEquals(listOf("en-US", "ja-JP"), auto.locales.map { it.pageLanguageCode })
        assertNotEquals(auto.token, ArtistVisualPolicy("US,JP").token)
        assertNotEquals(policy("US,JP").token, policy("JP,US").token)
        assertNotEquals(policy("US").token, policy("US", false).token)
    }

    @Test fun chinesePreferenceTriesOtherConfiguredChinesePagesAndFactsDoNotBlockTheBiography() {
        val calls = arrayListOf<String>()
        var profiles = 0
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                url == pageUrl("cn", "zh-Hans") -> page("")
                url == pageUrl("us", "zh-Hans") -> page(chinese)
                else -> error("Unexpected request $url")
            }
        }, { _, _, _, language -> profiles++; AmArtistDetails(genres = listOf("Pop"), languageCode = language.code) })
        val result = assertNotNull(catalog.resolve("test", policy("US,JP"), identity = identity))
        assertTrue(result.details?.biography.orEmpty().startsWith("这是一段"))
        assertEquals(listOf(pageUrl("cn", "zh-Hans"), pageUrl("us", "zh-Hans")), pageCalls(calls))
        assertEquals(2, profiles)
        assertTrue(catalog.cachedIsFresh("test", policy("US,JP"), identity))
    }

    @Test fun englishAndTraditionalChineseResponsesCannotSatisfySimplifiedChinesePreference() {
        val calls = arrayListOf<String>()
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                url == pageUrl("cn", "zh-Hans") -> page(english)
                url == pageUrl("jp", "zh-Hans") -> page(traditional)
                url == pageUrl("jp", "ja-JP") -> page(japanese)
                else -> error("Unexpected request $url")
            }
        }, noProfile)
        val result = assertNotNull(catalog.resolve("test", policy("JP"), identity = identity))
        assertEquals(japanese, result.details?.biography)
        assertEquals("ja-JP", result.details?.languageCode)
        assertEquals(listOf(pageUrl("cn", "zh-Hans"), pageUrl("jp", "zh-Hans"), pageUrl("jp", "ja-JP")), pageCalls(calls))
        assertEquals(PreferredArtistLanguage.TRADITIONAL_CHINESE,
            AppleMusicPageParser.localizedBiography(traditional, PreferredArtistLanguage.CHINESE, true)?.second)
    }

    @Test fun disabledChinesePreferenceUsesOnlyConfiguredNativePagesInTheirOrder() {
        val calls = arrayListOf<String>()
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                url == pageUrl("jp", "ja-JP") -> page("")
                url == pageUrl("us", "en-US") -> page(english)
                else -> error("Unexpected request $url")
            }
        }, noProfile)
        assertEquals(english, catalog.resolve("test", policy("JP,US", false), identity = identity)?.details?.biography)
        assertEquals(listOf(pageUrl("jp", "ja-JP"), pageUrl("us", "en-US")), pageCalls(calls))
    }

    @Test fun anUntranslatedNativePageDoesNotBeatTheNextRegionsNativeBiography() {
        val catalog = AppleMusicCatalog(null, log, { url -> when {
            "itunes.apple.com" in url -> lookup
            url == pageUrl("jp", "ja-JP") -> page(english.replace("regional", "Japanese page fallback"))
            else -> page(english)
        } }, noProfile)
        assertEquals(english, catalog.resolve("test", policy("JP,US", false), identity = identity)?.details?.biography)
        val jpOnly = catalog.resolve("test", policy("JP", false), identity = identity)
        assertEquals("en-US", jpOnly?.details?.languageCode)
        assertTrue(jpOnly?.details?.biography.orEmpty().contains("Japanese page fallback"))
    }

    @Test fun sameLanguageRegionsHaveIndependentCachesAndReorderingReselectsAfterReload() {
        val dir = Files.createTempDirectory("salt-regional-profiles").toFile()
        try {
            val calls = arrayListOf<String>()
            val usBio = english.replace("regional", "United States")
            val gbBio = english.replace("regional", "United Kingdom")
            val catalog = AppleMusicCatalog(dir, log, { url ->
                calls += url
                when {
                    "itunes.apple.com" in url -> lookup
                    "/us/artist/" in url -> page(usBio)
                    "/gb/artist/" in url -> page(gbBio)
                    else -> error("Unexpected request $url")
                }
            }, noProfile)
            assertEquals(usBio, catalog.resolve("test", policy("US", false), identity = identity)?.details?.biography)
            calls.clear()
            assertEquals(gbBio, catalog.resolve("test", policy("GB", false), identity = identity)?.details?.biography)
            assertEquals(listOf(pageUrl("gb", "en-GB")), calls)
            val reloaded = AppleMusicCatalog(dir, log, { error("Cached profiles and avatars must remain offline") }, noProfile)
            assertEquals(usBio, reloaded.cached("test", policy("US,GB", false), identity)?.details?.biography)
            assertEquals(gbBio, reloaded.cached("test", policy("GB,US", false), identity)?.details?.biography)
            assertEquals(gbBio, reloaded.resolve("test", policy("GB,US", false), identity = identity)?.details?.biography)
            assertTrue(reloaded.cachedIsFresh("test", policy("GB,US", false), identity))
            assertEquals("42", reloaded.resolveAvatar("test", identity)?.artistId)
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedChinesePageIsRetryableEvenAfterFallbackSurvivesReload() {
        val dir = Files.createTempDirectory("salt-regional-retry").toFile()
        try {
            val catalog = AppleMusicCatalog(dir, log, { url -> when {
                "itunes.apple.com" in url -> lookup
                url == pageUrl("cn", "zh-Hans") -> null
                url == pageUrl("jp", "zh-Hans") -> page("")
                else -> page(japanese)
            } }, noProfile)
            val selected = policy("JP")
            assertEquals(japanese, catalog.resolve("test", selected, identity = identity)?.details?.biography)
            val calls = arrayListOf<String>()
            val reloaded = AppleMusicCatalog(dir, log, { url -> calls += url; page(chinese) }, noProfile)
            assertFalse(reloaded.cachedIsFresh("test", selected, identity))
            assertEquals(japanese, reloaded.cached("test", selected, identity)?.details?.biography)
            assertTrue(reloaded.resolve("test", selected, identity = identity)?.details?.biography.orEmpty().startsWith("这是一段"))
            assertEquals(listOf(pageUrl("cn", "zh-Hans")), calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun profileSourceStillSuppliesFactsAndBiographiesWhenThePageHeaderIsMissingThem() {
        var invoked = false
        val catalog = AppleMusicCatalog(null, log, { url -> if ("itunes.apple.com" in url) lookup else page("") },
            { _, _, _, language -> invoked = true; AmArtistDetails(biography = japanese, origin = "Tokyo", languageCode = language.code) })
        val result = catalog.resolve("test", policy("JP", false), identity = identity)
        assertTrue(invoked)
        assertEquals(japanese, result?.details?.biography)
        assertEquals("Tokyo", result?.details?.origin)
    }

    @Test fun matchingChinesePageWinsOverAnEnglishProfileFallbackWithoutLosingProfileFacts() {
        val calls = arrayListOf<String>()
        val catalog = AppleMusicCatalog(null, log, { url -> calls += url; if ("itunes.apple.com" in url) lookup else page(chinese) },
            { _, _, _, _ -> AmArtistDetails(biography = english, origin = "Tokyo", languageCode = "en-US") })
        val result = catalog.resolve("test", policy("JP,US"), identity = identity)
        assertTrue(result?.details?.biography.orEmpty().startsWith("这是一段"))
        assertEquals("zh-Hans", result?.details?.languageCode)
        assertEquals("Tokyo", result?.details?.origin)
        assertEquals(listOf(pageUrl("cn", "zh-Hans")), pageCalls(calls))
    }

    @Test fun nativeKoreanFrenchGermanAndTraditionalChineseBiographiesSurviveCacheReload() {
        val biographies = mapOf(
            "KR" to "지역별 언어 설정과 아티스트 소개 선택을 검증하기 위해 직접 작성한 한국어 테스트 문장입니다.",
            "FR" to "Cette biographie originale a été écrite pour vérifier le choix de la langue de cette région.",
            "DE" to "Dieser eigenständige Testtext prüft die Künstlerbeschreibung und die Auswahl der regionalen Sprache.",
            "TW" to traditional,
        )
        val dir = Files.createTempDirectory("salt-native-language-cache").toFile()
        try {
            val catalog = AppleMusicCatalog(dir, log, { url ->
                if ("itunes.apple.com" in url) lookup else page(biographies.getValue(URI(url).path.split('/')[1].uppercase()))
            }, noProfile)
            biographies.forEach { (country, biography) ->
                val selected = policy(country, false)
                assertEquals(biography, catalog.resolve("test", selected, identity = identity)?.details?.biography)
                val reloaded = AppleMusicCatalog(dir, log, { error("A native profile must survive an offline reload") }, noProfile)
                assertEquals(biography, reloaded.resolve("test", selected, identity = identity)?.details?.biography)
                assertEquals(nativeArtistLocale(country).language.code, reloaded.cached("test", selected, identity)?.details?.languageCode)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun automaticVisualUsesNativeLocalesAndKeepsEarlierFailuresRetryable() {
        val calls = arrayListOf<String>()
        val payload = checkNotNull(javaClass.getResource("/apple-music/artist-motion-sanitized.json")).readText()
        val catalog = AppleMusicCatalog(null, log, { url ->
            calls += url
            when {
                "itunes.apple.com" in url -> lookup
                url == pageUrl("us", "en-US") -> null
                url == pageUrl("jp", "ja-JP") -> "<script id=\"serialized-server-data\">$payload</script>"
                else -> error("Unexpected request $url")
            }
        }, noProfile)
        val visual = ArtistVisualPolicy("US,JP", automaticLanguage = true)
        val result = catalog.resolve("test", policy("JP", false), identity = identity, visual = visual)
        assertNotNull(result?.motion)
        assertEquals(listOf(pageUrl("jp", "ja-JP"), pageUrl("us", "en-US"), pageUrl("jp", "ja-JP")), pageCalls(calls))
        assertFalse(catalog.cachedIsFresh("test", policy("JP", false), identity, visual))
    }

    @Test fun aLateRegionalProfileCannotRepopulateClearedMetadata() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val catalog = AppleMusicCatalog(null, log, { url ->
                if ("itunes.apple.com" in url) lookup else {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    page(japanese)
                }
            }, noProfile)
            val selected = policy("JP", false)
            val pending = worker.submit<AmArtist?> { catalog.resolve("test", selected, identity = identity) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            catalog.clear()
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertNull(catalog.cached("test", selected, identity))
            assertFalse(catalog.cachedIsFresh("test", selected, identity))
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
