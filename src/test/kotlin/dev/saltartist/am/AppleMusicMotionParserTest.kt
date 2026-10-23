package dev.saltartist.am

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic artwork URLs follow the scoped public Gorillaz header structure. */
class AppleMusicMotionParserTest {
    private val source = "https://music.apple.com/us/artist/test/42"
    private val fixture: String
        get() = checkNotNull(javaClass.getResource("/apple-music/artist-motion-sanitized.json")).readText()

    @Test
    fun currentFullscreenMovieAndItsPosterPrecedeTheLegacyStaticWideArtwork() {
        val artist = assertNotNull(AppleMusicPageParser.parseSerialized(fixture, "42", source, 1234L))
        val motion = assertNotNull(artist.motion)
        assertEquals(AmMotionKind.HLS, motion.kind)
        assertEquals("https://mvod.itunes.apple.com/test/fullscreen-master.m3u8", motion.url)
        assertEquals(motion.posterTemplate, artist.heroTemplate)
        assertTrue(artist.heroTemplate.orEmpty().contains("fullscreen-poster"))
        assertEquals("sr", artist.heroCrop)
        assertTrue(artist.heroIsWide)
        assertEquals(1234L, artist.motionCheckedAt)
        assertFalse(artist.avatarTemplate.orEmpty().contains("poster"))
    }

    @Test
    fun squareMovieUsesItsSquarePosterWhenNoWideMotionRemains() {
        val input = fixture.replace("\"wideVideoArtwork\"", "\"ignoredWideVideoArtwork\"")
        val artist = assertNotNull(AppleMusicPageParser.parseSerialized(input, "42", source, 1L))
        assertEquals(AmMotionKind.MP4, artist.motion?.kind)
        assertTrue(artist.heroTemplate.orEmpty().contains("square-poster"))
        assertFalse(artist.heroIsWide)
        assertEquals("bb", artist.motion?.posterCrop)
    }

    @Test
    fun missingMoviesStillKeepAValidCurrentPosterAndNeverBorrowRecommendations() {
        val input = fixture.replace("\"video\":", "\"ignoredVideo\":")
        val artist = assertNotNull(AppleMusicPageParser.parseSerialized(input, "42", source, 1L))
        assertNull(artist.motion)
        assertTrue(artist.heroTemplate.orEmpty().contains("fullscreen-poster"))
        val noMotion = fixture.replaceFirst("\"wideVideoArtwork\"", "\"ignoredWideVideoArtwork\"")
            .replace("\"videoArtwork\"", "\"ignoredVideoArtwork\"")
        assertNull(assertNotNull(AppleMusicPageParser.parseSerialized(noMotion, "42", source, 1L)).motion)
        assertNull(AppleMusicPageParser.parseSerialized(fixture, "999", source, 1L))
    }

    @Test
    fun mediaUrlsRequireKnownAppleCdnHttpsAndSupportedContainers() {
        assertNotNull(AppleMusicPageParser.safeMotionUrl("https://mvod.itunes.apple.com/movie.m3u8?region=US"))
        assertNotNull(AppleMusicPageParser.safeMotionUrl("https://video-ssl.itunes.apple.com/movie.MP4"))
        for (url in listOf("file:///movie.mp4", "http://mvod.itunes.apple.com/movie.m3u8",
            "https://mvod.itunes.apple.com.evil.test/movie.m3u8", "https://example.com/movie.mp4",
            "https://user:password@mvod.itunes.apple.com/movie.m3u8", "https://mvod.itunes.apple.com:444/movie.mp4",
            "https://mvod.itunes.apple.com/movie.m3u8#fragment", "https://mvod.itunes.apple.com/movie.html")) {
            assertNull(AppleMusicPageParser.safeMotionUrl(url), url)
        }
        assertNull(AppleMusicPageParser.safeMotionUrl("https://mvod.itunes.apple.com/" + "x".repeat(8200) + ".mp4"))
        assertNull(AppleMusicPageParser.parseHtml("x".repeat(12_000_001), "42", source, 1L))
    }
}
