package dev.saltartist.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistAlbumRouteTest {
    @Test
    fun routeUsesOriginalTrackMetadataInsteadOfDisplayAliases() {
        val track = ArtistTrack("id", "song", "A", "Original album", "A/B", "path", "", 2020, 1, 0, false)
        val album = ArtistAlbum("Renamed album", "A", 2020, listOf(track))
        assertEquals("Original album" to "A/B", album.hostRoute())
    }

    @Test
    fun routePreservesEmptyHostMetadataAndRejectsMissingTracks() {
        val track = ArtistTrack("id", "song", "A", "", "", "path", "", 0, 0, 0, false)
        assertEquals("" to "", ArtistAlbum("Unnamed", "A", 0, listOf(track)).hostRoute())
        assertNull(ArtistAlbum("Unnamed", "A", 0, emptyList()).hostRoute())
    }
}
