package dev.saltartist.am

import dev.saltartist.PluginLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistLanguageTest {
    private val chinese = "这是一段为解析测试创作的中文介绍。\\n\\n该艺人的资料由公开页面提供，缺失事实不作补全。"
    private val english = "This is an original English biography written only for the language selection tests. It contains no copied editorial text."
    private val fixture: String get() = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
    private val noProfile: (String, String?, PluginLog, PreferredArtistLanguage) -> AmArtistDetails? = { _, _, _, _ -> null }

    @Test
    fun preferredLanguageWinsAndDisallowedFallbackHasNoDetails() {
        val cn = artist("中文介绍", PreferredArtistLanguage.CHINESE)
        val en = artist(english, PreferredArtistLanguage.ENGLISH)
        val records = mapOf(PreferredArtistLanguage.CHINESE to cn, PreferredArtistLanguage.ENGLISH to en)
        assertEquals(cn, AppleMusicCatalog.selectLanguageRecord(records, ArtistLanguagePolicy()))
        assertEquals(en, AppleMusicCatalog.selectLanguageRecord(records, ArtistLanguagePolicy(PreferredArtistLanguage.ENGLISH)))
        assertNull(AppleMusicCatalog.selectLanguageRecord(mapOf(PreferredArtistLanguage.ENGLISH to en), ArtistLanguagePolicy(showOtherLanguages = false))?.details)
    }

    @Test
    fun aChineseUrlDoesNotMislabelItsEnglishBiography() {
        val page = fixture.replace(chinese, english)
        val parsed = assertNotNull(AppleMusicPageParser.parseHtml(page, "42", "https://music.apple.com/cn/artist/test/42", 1L,
            PreferredArtistLanguage.CHINESE, true))
        assertEquals(english, parsed.details?.biography)
        assertEquals("en-US", parsed.details?.languageCode)
        val japanese = "これは言語選択のテストのためだけに書かれた紹介文です。実在する記事の文章ではありません。"
        assertEquals(PreferredArtistLanguage.JAPANESE,
            AppleMusicPageParser.localizedBiography(japanese, PreferredArtistLanguage.JAPANESE, false)?.second)
    }

    @Test
    fun switchingPreferenceFetchesAndKeepsIndependentLanguageRecords() {
        val urls = arrayListOf<String>()
        val catalog = catalog(urls) { url -> if ("l=en-US" in url) fixture.replace(chinese, english) else fixture }
        val enPolicy = ArtistLanguagePolicy(PreferredArtistLanguage.ENGLISH, false)
        assertEquals(english, catalog.resolve("test", enPolicy)?.details?.biography)
        assertFalse(urls.any { "l=zh-Hans" in it })
        assertTrue(catalog.resolve("test", ArtistLanguagePolicy(showOtherLanguages = false))?.details?.biography.orEmpty().startsWith("这是一段"))
        assertEquals(english, catalog.cached("test", enPolicy)?.details?.biography)
        assertEquals("zh-Hans", catalog.cached("test", ArtistLanguagePolicy(showOtherLanguages = false))?.details?.languageCode)
    }

    @Test
    fun missingChineseBiographyCanUseRealEnglishButTheSettingCanHideIt() {
        val urls = arrayListOf<String>()
        val catalog = catalog(urls) { url -> if ("l=en-US" in url) fixture.replace(chinese, english) else fixture.replace(chinese, "") }
        val fallback = assertNotNull(catalog.resolve("test"))
        assertEquals(english, fallback.details?.biography)
        assertEquals("en-US", fallback.details?.languageCode)
        assertTrue(urls.any { "/us/artist/42?l=en-US" in it })
        val before = urls.size
        assertNull(catalog.resolve("test", ArtistLanguagePolicy(showOtherLanguages = false))?.details?.biography)
        assertEquals(before, urls.size)
    }

    @Test
    fun unsafeLegacyNameOnlyCacheCannotBypassFreshIdentityMatching() {
        val directory = Files.createTempDirectory("salt-artist-language-cache").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = artist("这是一段为旧缓存迁移专门创作的中文资料，不应被语言设置切换丢失。", PreferredArtistLanguage.CHINESE)
                .copy(artistId = "99", fetchedAt = now, detailsFetchedAt = now, details = AmArtistDetails(biography = "这是一段为旧缓存迁移专门创作的中文资料，不应被语言设置切换丢失。"))
            val encoded = AppleMusicCache.encode("test", old)
            assertEquals("2", encoded.split('\t')[8])
            directory.resolve("apple-music-cache.tsv").writeText(encoded + "\n")
            val requests = arrayListOf<String>()
            val catalog = AppleMusicCatalog(directory, PluginLog(null), { url ->
                requests += url
                if ("itunes.apple.com" in url) searchResult else fixture
            }, noProfile)
            assertEquals("42", catalog.resolve("test", ArtistLanguagePolicy(showOtherLanguages = false))?.artistId)
            assertTrue(requests.isNotEmpty())
            assertNull(catalog.cached("test", ArtistLanguagePolicy(PreferredArtistLanguage.ENGLISH, false))?.details)
            catalog.saveCache()
            val reloaded = AppleMusicCatalog(directory, PluginLog(null))
            assertEquals("zh-Hans", reloaded.cached("test")?.details?.languageCode)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun scopedV2ChineseAndV3EnglishCachesKeepTheirLanguagesAfterReload() {
        val directory = Files.createTempDirectory("salt-artist-scoped-language-cache").toFile()
        try {
            val now = System.currentTimeMillis()
            val scope = ArtistIdentityPolicy(enabled = false).artistCacheKey("test")
            val cn = artist("这是一段为命名空间中文缓存测试创作的正文，重载后仍应保留中文。", PreferredArtistLanguage.CHINESE)
                .let { it.copy(fetchedAt = now, detailsFetchedAt = now, details = it.details?.copy(languageCode = null)) }
            val en = artist(english, PreferredArtistLanguage.ENGLISH).copy(fetchedAt = now, detailsFetchedAt = now)
            directory.resolve("apple-music-cache.tsv").writeText(
                AppleMusicCache.encode("$scope|zh-Hans", cn) + "\n" + AppleMusicCache.encode("$scope|en-US", en) + "\n",
            )
            val catalog = AppleMusicCatalog(directory, PluginLog(null), { error("Scoped fresh records must not fetch") }, noProfile)
            assertEquals(cn.details?.biography, catalog.resolve("test", ArtistLanguagePolicy(showOtherLanguages = false))?.details?.biography)
            assertEquals(english, catalog.cached("test", ArtistLanguagePolicy(PreferredArtistLanguage.ENGLISH, false))?.details?.biography)
            catalog.saveCache()
            val reloaded = AppleMusicCatalog(directory, PluginLog(null))
            assertEquals("zh-Hans", reloaded.cached("test")?.details?.languageCode)
            assertEquals("en-US", reloaded.cached("test", ArtistLanguagePolicy(PreferredArtistLanguage.ENGLISH, false))?.details?.languageCode)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun v3CacheRetainsAnEnglishBiographyAndLanguageTag() {
        val original = artist(english, PreferredArtistLanguage.ENGLISH)
        val encoded = AppleMusicCache.encode("test|en-US", original)
        assertEquals("3", encoded.split('\t')[8])
        assertEquals("test|en-US" to original, AppleMusicCache.decode(encoded))
    }

    @Test
    fun listAvatarLookupNeverCallsProfileOrDownloadsArtworkAndIsCached() {
        val urls = arrayListOf<String>()
        val catalog = AppleMusicCatalog(null, PluginLog(null), { url ->
            urls += url
            if ("itunes.apple.com" in url) searchResult else fixture
        }, { _, _, _, _ -> error("Avatar lookup must not invoke the profile client") })
        val avatar = assertNotNull(catalog.resolveAvatar("test"))
        assertTrue(avatar.template.contains("/test/avatar/"))
        assertEquals("sr", avatar.crop)
        assertEquals(avatar, catalog.resolveAvatar("test"))
        assertEquals(2, urls.size)
        assertFalse(urls.any { "/assets/" in it || "mzstatic.com" in it || "amp-api" in it })
        assertNull(catalog.cached("test")?.details)
    }

    @Test
    fun blankOptionalValuesDoNotCountAsArtistInformation() {
        assertFalse(AmArtistDetails(biography = " ", born = "", origin = "\n", genres = listOf(" ")).hasContent)
    }

    @Test
    fun anUntranslatedPlaceCanBeShownWithoutReplacingThePreferredBiography() {
        val cn = artist("中文介绍", PreferredArtistLanguage.CHINESE).copy(
            details = AmArtistDetails(biography = "中文介绍", origin = "Nashville, TN",
                genres = listOf("Pop", "Untranslated Experimental Genre"), languageCode = "zh-Hans"),
        )
        val records = mapOf(PreferredArtistLanguage.CHINESE to cn)
        assertEquals("Nashville, TN", AppleMusicCatalog.selectLanguageRecord(records, ArtistLanguagePolicy())?.details?.origin)
        val strict = AppleMusicCatalog.selectLanguageRecord(records, ArtistLanguagePolicy(showOtherLanguages = false))?.details
        assertNull(strict?.origin)
        assertEquals("中文介绍", strict?.biography)
        assertEquals(listOf("流行"), strict?.genres)
    }

    private fun catalog(urls: MutableList<String>, page: (String) -> String) =
        AppleMusicCatalog(null, PluginLog(null), { url -> urls += url; if ("itunes.apple.com" in url) searchResult else page(url) }, noProfile)

    private fun artist(bio: String, language: PreferredArtistLanguage) = AmArtist("42", "hero", "bb", true,
        null, "bb", 1L, details = AmArtistDetails(biography = bio, languageCode = language.code), detailsFetchedAt = 1L)

    private val searchResult = """{"results":[{"artistName":"test","artistLinkUrl":"https://music.apple.com/cn/artist/test/42"}]}"""
}
