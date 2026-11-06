package dev.saltartist.am

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChineseRuntimeCompatibilityTest {
    @Test fun chinesePageAndSavedBiographyWorkWithoutOptionalCharsetProviders() {
        if (System.getProperty("saltartist.hostRuntimeTest") == "true") {
            assertFalse(Charset.isSupported("GB2312"))
            assertFalse(Charset.isSupported("Big5"))
        }
        val html = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
        val parsed = assertNotNull(AppleMusicPageParser.parseHtml(html, "42",
            "https://music.apple.com/cn/artist/42?l=zh-Hans", 100L, PreferredArtistLanguage.CHINESE, true))
        assertTrue(parsed.details?.biography.orEmpty().startsWith("这是一段"))
        val restored = assertNotNull(AppleMusicCache.decode(AppleMusicCache.encode("test|zh-Hans", parsed))).second
        assertEquals(parsed.details?.biography, restored.details?.biography)
        assertEquals(parsed.artistId, restored.artistId)
    }
}
