package dev.saltartist.am

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleMusicPageParserTest {
    private val source = "https://music.apple.com/cn/artist/test/42?l=zh-Hans"
    private val fixture: String
        get() = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
    private val serialized: String
        get() = fixture.substringAfter("<script id=\"serialized-server-data\" type=\"application/json\">").substringBefore("</script>")

    @Test
    fun scopesBiographyAvatarAndGenresToTheRequestedArtist() {
        val result = assertNotNull(AppleMusicPageParser.parseHtml(fixture, "42", source, 1234L))
        val details = assertNotNull(result.details)
        assertTrue(details.biography.orEmpty().startsWith("这是一段为解析测试创作的中文介绍。"))
        assertFalse(details.biography.orEmpty().contains("其他艺人"))
        assertEquals(listOf("嘻哈/说唱", "国语流行"), details.genres)
        assertEquals("https://music.apple.com/cn/artist/test/42", details.sourceUrl)
        assertTrue(result.avatarTemplate.orEmpty().contains("/test/avatar/"))
        assertEquals("sr", result.avatarCrop)
        assertEquals(1234L, result.detailsFetchedAt)
        assertNull(details.born)
        assertNull(details.formed)
        assertNull(details.origin)
    }

    @Test
    fun nullWideArtworkAndLogoNeverConsumeTheFollowingObjectUrl() {
        val result = assertNotNull(AppleMusicPageParser.parseHtml(fixture, "42", source, 1L))
        assertFalse(result.heroIsWide)
        assertTrue(result.heroTemplate.orEmpty().contains("/test/square/"))
        assertFalse(result.heroTemplate.orEmpty().contains("backdrop"))
        assertNull(result.logoTemplate)
    }

    @Test
    fun wideVideoPreviewIsARealWideFallbackWhenThePhotoFieldIsNull() {
        val input = fixture.replace(
            "\"wideVideoArtwork\":null",
            """"wideVideoArtwork":{"dictionary":{"motionArtistWide16x9":{"previewFrame":{"url":"https://is1-ssl.mzstatic.com/image/thumb/test/wide/{w}x{h}{c}.{f}"}}},"cropStyle":"sr"}""",
        )
        val result = assertNotNull(AppleMusicPageParser.parseHtml(input, "42", source, 1L))
        assertTrue(result.heroIsWide)
        assertTrue(result.heroTemplate.orEmpty().contains("/test/wide/"))
    }

    @Test
    fun rejectsWrongRootIdHeaderIdCanonicalUrlAndUntrustedSource() {
        assertNull(AppleMusicPageParser.parseHtml(fixture, "999", source, 1L))
        assertNull(AppleMusicPageParser.parseHtml(fixture.replace("\"id\":\"42\"", "\"id\":\"43\""), "42", source, 1L))
        assertNull(AppleMusicPageParser.parseHtml(fixture.replace("\"canonicalURL\":\"https://music.apple.com/cn/artist/test/42\"", "\"canonicalURL\":\"https://music.apple.com/cn/artist/other/999\""), "42", source, 1L))
        assertNull(AppleMusicPageParser.parseHtml(fixture, "42", "https://example.com/cn/artist/test/42", 1L))
    }

    @Test
    fun anEnglishOrJapaneseHeaderDoesNotBorrowChineseFromRecommendations() {
        val bio = "这是一段为解析测试创作的中文介绍。\\n\\n该艺人的资料由公开页面提供，缺失事实不作补全。"
        val english = "An artist with a long English profile and a quoted Chinese title 测试作品."
        val result = assertNotNull(AppleMusicPageParser.parseSerialized(serialized.replace(bio, english), "42", source, 1L))
        assertNull(result.details)
        assertNotNull(result.heroTemplate)
        assertNull(AppleMusicPageParser.chineseBiography("日本の音楽家であり東京から活動する歌手です。新しい作品を作り続けています。"))
    }

    @Test
    fun aValidHeaderCanHaveOnlyBiographyOrNoOptionalData() {
        val minimal = """{"data":[{"intent":{"${'$'}kind":"ArtistDetailPageIntent","contentDescriptor":{"kind":"artist","identifiers":{"storeAdamID":"42"}}},"data":{"sections":[{"itemKind":"artistDetailHeader","items":[{"id":"42","contentDescriptor":{"kind":"artist","identifiers":{"storeAdamID":"42"}},"bio":"这是一段原创的中文资料占位，用于验证没有图片时仍然可以读取文字。"}]}]}}]}"""
        val biographyOnly = assertNotNull(AppleMusicPageParser.parseSerialized(minimal, "42", source, 1L))
        assertNull(biographyOnly.heroTemplate)
        assertNull(biographyOnly.avatarTemplate)
        assertNotNull(biographyOnly.details?.biography)
        val empty = minimal.replace("这是一段原创的中文资料占位，用于验证没有图片时仍然可以读取文字。", "")
        assertNull(assertNotNull(AppleMusicPageParser.parseSerialized(empty, "42", source, 1L)).details)
    }

    @Test
    fun searchMatchesNameAndLinkWithinTheSameResultObject() {
        val input = """{"results":[{"artistName":"Allday Project","artistLinkUrl":"https://music.apple.com/cn/artist/other/999"},{"artistLinkUrl":"https://music.apple.com/us/artist/allday/42?uo=4","artistName":"Allday"}]}"""
        assertEquals("https://music.apple.com/us/artist/allday/42", AppleMusicPageParser.searchArtistLink(input) { it == "Allday" })
    }

    @Test
    fun formatsOnlyUnambiguousValidDates() {
        assertEquals("1989年12月13日", AppleMusicPageParser.formatDate("1989-12-13"))
        assertEquals("1989年12月13日", AppleMusicPageParser.formatDate("December 13, 1989"))
        assertEquals("2001年2月", AppleMusicPageParser.formatDate("2001-02"))
        assertEquals("1990年", AppleMusicPageParser.formatDate("1990"))
        assertEquals("2000年2月29日", AppleMusicPageParser.formatDate("2000年2月29日"))
        assertNull(AppleMusicPageParser.formatDate("2001-02-30"))
        assertNull(AppleMusicPageParser.formatDate("February 30, 2001"))
        assertNull(AppleMusicPageParser.formatDate("2001年2月30日"))
        assertNull(AppleMusicPageParser.formatDate("01/02/2001"))
    }

    @Test
    fun jsonReaderHandlesEscapesAndRejectsTrailingOrMalformedData() {
        val value = AppleMusicJson.read("""{"name":"\u6d4b\u8bd5\n\"name\"","values":[null,true,-1.25e2]}""").jsonObject()
        assertEquals("测试\n\"name\"", value.textAt("name"))
        assertTrue(runCatching { AppleMusicJson.read("{\"x\":1,}") }.isFailure)
        assertTrue(runCatching { AppleMusicJson.read("[] trailing") }.isFailure)
        assertNull(AppleMusicPageParser.parseSerialized("not JSON", "42", source, 1L))
    }
}
