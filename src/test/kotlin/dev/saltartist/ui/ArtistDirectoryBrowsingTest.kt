package dev.saltartist.ui

import dev.saltartist.bridge.DirectoryArtist
import dev.saltartist.settings.*
import kotlin.test.*

class ArtistDirectoryBrowsingTest {
    private val artists = (0..99).map { DirectoryArtist("Artist $it") }
    private val prefs = ArtistDirectoryPreferences()

    @Test fun returnsToTheSameArtistAndOffsetInEveryView() {
        val browsing = ArtistDirectoryBrowsing()
        for (view in ArtistDirectoryView.entries) {
            val key = DirectoryScrollKey(prefs.copy(view = view), "Artist")
            browsing.save(key, artists, 42, 31)
            assertEquals(DirectoryScrollPosition(42, 31), browsing.restore(key, artists))
            // New native ready-state instances after navigation preserve the viewport.
            assertEquals(DirectoryScrollPosition(42, 31), browsing.restore(key, artists.map { it.copy() }))
        }
    }

    @Test fun changedSearchOrLayoutStartsAtTopButDoesNotEraseTheSavedViewport() {
        val browsing = ArtistDirectoryBrowsing()
        val original = DirectoryScrollKey(prefs, "")
        browsing.save(original, artists, 40, 12)
        for (changed in listOf(original.copy(query = "Other"), original.copy(sort = ArtistDirectorySort.ADDED),
            original.copy(size = ArtistDirectorySize.SMALL), original.copy(view = ArtistDirectoryView.LIST))) {
            assertEquals(DirectoryScrollPosition(), browsing.restore(changed, artists))
        }
        assertEquals(DirectoryScrollPosition(40, 12), browsing.restore(original, artists))
    }

    @Test fun reorderedOrRemovedArtistsUseTheAnchorOrNearestValidIndex() {
        val browsing = ArtistDirectoryBrowsing()
        val key = DirectoryScrollKey(prefs, "")
        browsing.save(key, artists, 40, 12)
        assertEquals(DirectoryScrollPosition(41, 12), browsing.restore(key, listOf(DirectoryArtist("New")) + artists))
        assertEquals(DirectoryScrollPosition(8, 12), browsing.restore(key, artists.take(9)))
        assertEquals(DirectoryScrollPosition(), browsing.restore(key, emptyList()))
        browsing.save(key, emptyList(), 0, 0)
        assertEquals(DirectoryScrollPosition(40, 12), browsing.restore(key, artists))
    }
}
