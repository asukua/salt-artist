package dev.saltartist.am

import dev.saltartist.PluginLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleMusicCacheTest {
    private val now = 10_000L
    private val source = "https://music.apple.com/cn/artist/test/42"
    private fun artist() = AmArtist("42", "hero", "sr", true, null, "bb", now, motionCheckedAt = now)

    @Test
    fun legacyEightColumnCacheKeepsImagesButRequiresAProfileRefresh() {
        val decoded = assertNotNull(AppleMusicCache.decode("test\t42\thero\tsr\t1\t-\tbb\t10000")).second
        assertEquals("hero", decoded.heroTemplate)
        assertTrue(decoded.heroIsWide)
        assertNull(decoded.details)
        assertNull(decoded.avatarTemplate)
        assertEquals(0L, decoded.detailsFetchedAt)
        assertFalse(AppleMusicCache.isFresh(decoded, now, 1000L))
    }

    @Test
    fun currentCacheRoundTripsChineseParagraphsAndOptionalFactsWithoutExtraTsvLines() {
        val original = artist().copy(
            avatarTemplate = "avatar", avatarCrop = "sr", detailsFetchedAt = now,
            details = AmArtistDetails(
                biography = "这是一段用于缓存测试的原创中文介绍。\n\n第二段继续验证换行不会变成额外缓存记录。",
                born = "1989年12月13日", formed = "2000年", origin = "中国台湾",
                genres = listOf("国语流行", "摇滚"), sourceUrl = source,
            ),
        )
        val encoded = AppleMusicCache.encode("test", original)
        assertEquals(24, encoded.split('\t').size)
        assertFalse(encoded.contains('\n'))
        assertEquals("test" to original, AppleMusicCache.decode(encoded))
        assertTrue(AppleMusicCache.isFresh(original, now, 1000L))
    }

    @Test
    fun aCheckedEmptyChineseProfileIsCachedWithoutInventingAnEnglishFallback() {
        val empty = artist().copy(detailsFetchedAt = now)
        val decoded = assertNotNull(AppleMusicCache.decode(AppleMusicCache.encode("test", empty))).second
        assertNull(decoded.details)
        assertTrue(AppleMusicCache.isFresh(decoded, now, 1000L))
        assertFalse(AppleMusicCache.isFresh(decoded, now + 1001, 1000L))
        assertFalse(AppleMusicCache.isFresh(decoded, now - 1, 1000L))
        assertNull(AppleMusicCache.decode("incomplete\t42"))
    }

    @Test
    fun v2AndV3RecordsKeepFactsButRequireOneMotionHeaderRefresh() {
        val original = artist().copy(detailsFetchedAt = now, details = AmArtistDetails(
            biography = "这是一段用于旧版本缓存迁移测试的原创中文介绍。", languageCode = "zh-Hans",
        ))
        val current = AppleMusicCache.encode("test", original).split('\t')
        for ((version, count) in listOf("2" to 18, "3" to 19)) {
            val legacy = current.take(count).toMutableList().apply { this[8] = version }.joinToString("\t")
            val decoded = assertNotNull(AppleMusicCache.decode(legacy)).second
            assertEquals(original.details?.biography, decoded.details?.biography)
            assertEquals(now, decoded.detailsFetchedAt)
            assertEquals(0L, decoded.motionCheckedAt)
            assertNull(decoded.motion)
            assertFalse(AppleMusicCache.isFresh(decoded, now, 1000L))
        }
    }

    @Test
    fun motionRoundTripsWithItsOwnPosterAndRejectsUnsafeOrMismatchedMovieFields() {
        val motion = AmArtistMotion("https://mvod.itunes.apple.com/test/master.m3u8", AmMotionKind.HLS,
            "https://is1-ssl.mzstatic.com/image/thumb/test/poster/{w}x{h}{c}.{f}", "sr")
        val original = artist().copy(motion = motion, detailsFetchedAt = now)
        val encoded = AppleMusicCache.encode("test", original)
        assertEquals("test" to original, AppleMusicCache.decode(encoded))
        val mismatch = encoded.split('\t').toMutableList().apply { this[19] = "MP4" }.joinToString("\t")
        assertNull(assertNotNull(AppleMusicCache.decode(mismatch)).second.motion)
        val unsafe = original.copy(motion = motion.copy(url = "https://example.com/master.m3u8"))
        assertNull(assertNotNull(AppleMusicCache.decode(AppleMusicCache.encode("test", unsafe))).second.motion)
        assertNull(AppleMusicCache.decode("x".repeat(256_001)))
    }

    @Test
    fun chineseStorefrontGenresRemainPreferredWhenAnotherLocalizedPageSuppliesBiography() {
        val cn = artist().copy(heroIsWide = false, details = AmArtistDetails(genres = listOf("国语流行"), sourceUrl = source), avatarTemplate = "cn-avatar")
        val us = artist().copy(details = AmArtistDetails(biography = "这是一段来自另一个中文地区页面的原创测试介绍。", genres = listOf("流行"), sourceUrl = "https://music.apple.com/us/artist/test/42?l=zh-Hans"))
        val merged = AppleMusicCatalog.mergePages(listOf(cn, us), null, now)
        assertEquals(listOf("国语流行"), merged.details?.genres)
        assertEquals(us.details?.biography, merged.details?.biography)
        assertEquals(us.details?.sourceUrl, merged.details?.sourceUrl)
        assertEquals("cn-avatar", merged.avatarTemplate)
        assertTrue(merged.heroIsWide)
        assertTrue(runCatching { AppleMusicCatalog.mergePages(listOf(cn, us.copy(artistId = "999")), null, now) }.isFailure)
    }

    @Test
    fun publicProfileFactsAugmentRatherThanReplaceTheChinesePageBiography() {
        val page = artist().copy(details = AmArtistDetails(biography = "这是一段为页面与公开资料合并测试创作的中文介绍。", sourceUrl = source))
        val profile = AmArtistDetails(born = "2000年6月3日", genres = listOf("另类音乐"), sourceUrl = source)
        val merged = AppleMusicCatalog.mergePages(listOf(page), null, now, profile)
        assertEquals(page.details?.biography, merged.details?.biography)
        assertEquals("2000年6月3日", merged.details?.born)
        assertEquals(listOf("另类音乐"), merged.details?.genres)
        assertNull(merged.details?.origin)
    }

    @Test
    fun verifiedNullImageFieldsClearUntrustedLegacyWindowParserValues() {
        val old = artist().copy(logoTemplate = "incorrect-following-object")
        val page = artist().copy(heroTemplate = null, heroIsWide = false, detailsFetchedAt = now)
        val merged = AppleMusicCatalog.mergePages(listOf(page), old, now)
        assertNull(merged.heroTemplate)
        assertNull(merged.logoTemplate)
    }

    @Test
    fun changedImageShapeInvalidatesOldFileAndForgetAlsoRemovesAvatar() {
        val dir = Files.createTempDirectory("salt-artist-am-cache").toFile()
        try {
            val catalog = AppleMusicCatalog(dir, PluginLog(null))
            catalog.ensureImageDir()
            val hero = assertNotNull(catalog.heroFile("test"))
            val logo = assertNotNull(catalog.logoFile("test"))
            val avatar = assertNotNull(catalog.avatarFile("test"))
            hero.writeText("old wide file")
            logo.writeText("same logo")
            avatar.writeText("same avatar")
            val old = artist().copy(logoTemplate = "logo", avatarTemplate = "avatar")
            catalog.invalidateChangedImages("test", old, old.copy(heroIsWide = false))
            assertFalse(hero.exists())
            assertTrue(logo.exists())
            assertTrue(avatar.exists())
            assertTrue(catalog.forget("test"))
            assertFalse(logo.exists())
            assertFalse(avatar.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
