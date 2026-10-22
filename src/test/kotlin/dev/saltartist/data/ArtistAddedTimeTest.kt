package dev.saltartist.data

import dev.saltartist.PluginLog
import dev.saltartist.settings.ArtistProfileStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistAddedTimeTest {
    @Test
    fun readsTheNativeAddedTimeFieldByTrackId() {
        val times = libraryAddedTimesFromHostSnapshot(
            listOf(HostTrack("first", 1_700_000_000_000L), HostTrack("second", 1_800_000_000_000L)),
        )
        assertEquals(mapOf("first" to 1_700_000_000_000L, "second" to 1_800_000_000_000L), times)
    }

    @Test
    fun missingOrUnreadableNativeMetadataStaysUnknown() {
        val times = libraryAddedTimesFromHostSnapshot(
            listOf(null, Any(), HostTrack("zero", 0), HostTrack("negative", -1), HostTrack("", 7), UnreadableTrack()),
        )
        assertEquals(emptyMap(), times)
        assertEquals(0L, track("unknown").libraryAddedTime)
    }

    @Test
    fun oneUnreadableRecordDoesNotDiscardOtherKnownTimes() {
        assertEquals(
            mapOf("known" to 10L),
            libraryAddedTimesFromHostSnapshot(listOf(UnreadableTrack(), HostTrack("known", 10L))),
        )
    }

    @Test
    fun latestAdditionCanBeAnOlderRelease() {
        val newerRelease = album("New release", 2026, track("new", 100))
        val olderReleaseAddedLater = album("Old release", 2006, track("old", 200))
        assertEquals(olderReleaseAddedLater, selectLatestAddedAlbum(listOf(newerRelease, olderReleaseAddedLater)))
    }

    @Test
    fun albumUsesItsLatestKnownTrackTimeAndUnknownDoesNotWin() {
        val mixed = album("Mixed", 2020, track("first", 10), track("later", 30), track("unknown"))
        val other = album("Other", 2026, track("other", 20))
        val unknown = album("A unknown", 2030, track("no-time"))
        assertEquals(30L, mixed.latestLibraryAddedTime)
        assertEquals(mixed, selectLatestAddedAlbum(listOf(unknown, other, mixed)))
        assertEquals(0L, album("Empty", 2025).latestLibraryAddedTime)
    }

    @Test
    fun unknownTimesUseStableNamesWithoutInferringFromReleaseYear() {
        val stableFirst = album("A album", 2000, track("first"))
        val newerRelease = album("Z album", 2026, track("second"))
        assertEquals(stableFirst, selectLatestAddedAlbum(listOf(newerRelease, stableFirst)))
        assertEquals(stableFirst, selectLatestAddedAlbum(listOf(stableFirst, newerRelease)))
        assertNull(selectLatestAddedAlbum(emptyList()))
    }

    @Test
    fun editorAddingExistingSongsDoesNotCreateANewLibraryTimestamp() {
        val directory = Files.createTempDirectory("salt-artist-added-time-test-")
        try {
            val original = track("original", 100).copy(artist = "Artist", albumArtist = "Artist", album = "Original")
            val knownExtra = track("known-extra", 20).copy(artist = "Other", albumArtist = "Other", album = "Known extra")
            val unknownExtra = track("unknown-extra").copy(artist = "Other", albumArtist = "Other", album = "Unknown extra")
            val source = ArtistPageModel(
                name = "Artist",
                tracks = listOf(original),
                albums = listOf(album("Original", 2024, original)),
                genres = emptyList(),
                yearRange = 2024..2024,
            )
            val store = ArtistProfileStore(directory.toFile(), PluginLog(null))
            store.addTrack("Artist", knownExtra.id)
            store.addTrack("Artist", unknownExtra.id)

            val edited = store.apply(source, listOf(original, knownExtra, unknownExtra))
            assertEquals(20L, edited.tracks.single { it.id == knownExtra.id }.libraryAddedTime)
            assertEquals(0L, edited.tracks.single { it.id == unknownExtra.id }.libraryAddedTime)
            assertEquals("Original", selectLatestAddedAlbum(edited.albums)?.title)
        } finally {
            Files.deleteIfExists(directory.resolve("artist-profiles.ini"))
            Files.deleteIfExists(directory)
        }
    }

    class HostTrack(private val identity: String, private val added: Long) {
        fun getId(): String = identity
        fun getAddedTime(): Long = added
        fun getModifiedTime(): Long = Long.MAX_VALUE
    }

    class UnreadableTrack {
        fun getId(): String = "unreadable"
        fun getAddedTime(): Long = error("Unavailable host metadata")
    }

    private fun album(title: String, year: Int, vararg tracks: ArtistTrack) =
        ArtistAlbum(title, "Artist", year, tracks.toList())

    private fun track(id: String, libraryAddedTime: Long = 0L) = ArtistTrack(
        id = id,
        title = id,
        artist = "Artist",
        album = id,
        albumArtist = "Artist",
        path = "",
        genre = "",
        year = 2024,
        number = 1,
        durationMs = 120_000,
        isFavorite = false,
        libraryAddedTime = libraryAddedTime,
    )
}
