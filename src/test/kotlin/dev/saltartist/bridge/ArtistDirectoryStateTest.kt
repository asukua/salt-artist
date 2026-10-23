package dev.saltartist.bridge

import dev.saltartist.am.artistIdentityKey
import dev.saltartist.data.ArtistTrack
import dev.saltartist.settings.*
import kotlin.test.*

class ArtistDirectoryStateTest {
    class Artist(private val name: String) { fun getName() = name }
    class Group(private val items: List<Artist>) { fun items() = items; operator fun component2() = items }
    class Ready(private val groups: List<Group>) { fun groups() = groups }

    @Test fun readsOnlyTheNativeGroupItemsInOrder() {
        val state = Ready(listOf(Group(listOf(Artist("B"), Artist("AC/DC"))), Group(listOf(Artist("A")))))
        assertEquals(listOf("B", "AC/DC", "A"), directoryArtistsFromState(state).map { it.name })
    }

    @Test fun filteringAndRemovalPreserveDistinctPunctuationNames() {
        val names = listOf("AC/DC", "AC DC", "Other").map(::DirectoryArtist)
        val prefs = ArtistDirectoryPreferences(hidden = setOf("ac/dc"))
        assertEquals(listOf("AC DC"), orderDirectoryArtists(names, prefs, emptyMap(), emptySet(), false, "ac").map { it.name })
        assertEquals(3, names.size)
    }

    @Test fun nativeFavoritesAlsoRespectTheFavoritesSwitch() {
        val source = listOf(DirectoryArtist("A"), DirectoryArtist("Z", favorite = true))
        val prefs = ArtistDirectoryPreferences()
        assertEquals(listOf("Z", "A"), orderDirectoryArtists(source, prefs, emptyMap(), emptySet(), true, "").map { it.name })
        assertEquals(listOf("A", "Z"), orderDirectoryArtists(source, prefs, emptyMap(), emptySet(), false, "").map { it.name })
    }

    @Test fun favoritesArePartitionedAfterTheRequestedSort() {
        val names = listOf("C", "A", "B").map(::DirectoryArtist)
        val stats = mapOf("c" to DirectoryStats(tracks = 4), "a" to DirectoryStats(tracks = 1), "b" to DirectoryStats(tracks = 8))
        val prefs = ArtistDirectoryPreferences(sort = ArtistDirectorySort.TRACKS)
        assertEquals(listOf("A", "B", "C"), orderDirectoryArtists(names, prefs, stats, setOf("a"), true, "").map { it.name })
        assertEquals(listOf("B", "C", "A"), orderDirectoryArtists(names, prefs, stats, setOf("a"), false, "").map { it.name })
    }

    @Test fun recentAddedUsesLibraryTimeAndKeepsUnknownLast() {
        val names = listOf("Unknown", "Old", "New").map(::DirectoryArtist)
        val stats = mapOf("old" to DirectoryStats(added = 4), "new" to DirectoryStats(added = 10))
        val prefs = ArtistDirectoryPreferences(sort = ArtistDirectorySort.ADDED)
        assertEquals(listOf("New", "Old", "Unknown"), orderDirectoryArtists(names, prefs, stats, emptySet(), false, "").map { it.name })
        assertEquals(names, orderDirectoryArtists(names, prefs.copy(sort = ArtistDirectorySort.NATIVE), stats, emptySet(), false, ""))
    }

    @Test fun statisticsDeduplicateTracksAcrossArtistAndAlbumArtist() {
        fun track(id: String, album: String, added: Long) = ArtistTrack(id, id, "AC/DC", album, "AC/DC", "", "", 2026, 1, 100, false, added)
        val result = directoryStatistics(listOf(track("1", "One", 8), track("2", "One", 0), track("3", "Two", 4)))
        assertEquals(DirectoryStats(3, 2, 8, "1"), result[artistIdentityKey("AC/DC")])
    }

    @Test fun rejectsAmbiguousNativeStateRatherThanReplacingTheDirectoryWithGuesses() {
        assertFails { directoryArtistsFromState(Any()) }
        assertFails { directoryArtistsFromState(Ready(listOf(Group(listOf(Artist("A"), Artist("A")))))) }
    }

    @Test fun leavingTheReadySubtreeClearsTheOverlayAndSameStateCanReenter() {
        val bridge = ArtistDirectoryState(dev.saltartist.PluginLog(null))
        val ready = Ready(listOf(Group(listOf(Artist("A")))))
        bridge.capture(ready)
        assertEquals(listOf(DirectoryArtist("A")), bridge.artists)
        bridge.reset()
        assertNull(bridge.artists)
        bridge.capture(ready)
        assertEquals(1, bridge.artists?.size)
        bridge.capture(Any())
        assertNull(bridge.artists)
    }
}
