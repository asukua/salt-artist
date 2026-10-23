package dev.saltartist.artwork

import dev.saltartist.am.AmArtistDetails
import dev.saltartist.am.AmArtistMotion
import dev.saltartist.am.AmMotionKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistArtworkRetryTest {
    @Test
    fun availableFactsOrVideoDoNotHideATemporaryPosterDownloadFailure() {
        val result = ArtworkStore.AmArt(null, null,
            details = AmArtistDetails(biography = "Available biography"),
            motion = AmArtistMotion("https://mvod.itunes.apple.com/artist.m3u8", AmMotionKind.HLS),
            incompleteImages = true)
        assertTrue(result.retryable)
    }

    @Test
    fun anArtistWithNoPublishedImagesCanKeepItsValidBiography() {
        assertFalse(ArtworkStore.AmArt(null, null, details = AmArtistDetails(biography = "Available biography")).retryable)
        assertTrue(ArtworkStore.AmArt(null, null).retryable)
    }
}
