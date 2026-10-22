package dev.saltartist.settings

import dev.saltartist.PluginLog
import dev.saltartist.data.ArtistAlbum
import dev.saltartist.data.ArtistPageModel
import dev.saltartist.data.ArtistTrack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistProfileStoreTest {
    private val originalArtist = "Original Artist"
    private val originalTrack = track("original", "Original song", originalArtist, "Original album")
    private val addedTrack = track("added", "Added song", "Another Artist", "Added album")
    private val originalModel = ArtistPageModel(
        name = originalArtist,
        tracks = listOf(originalTrack),
        albums = listOf(ArtistAlbum("Original album", originalArtist, 2024, listOf(originalTrack))),
        genres = emptyList(),
        yearRange = 2024..2024,
    )
    private val library = listOf(originalTrack, addedTrack)

    @Test
    fun customLogoPersistsUnderOriginalIdentityAndCanBeReset() = withStore { store, directory ->
        val image = directory.resolve("artist-logo.png")
        Files.write(image, byteArrayOf(1, 2, 3))
        try {
            store.setName(originalArtist, "Display Artist")
            store.setLogo(originalArtist, image.toFile())
            val reloaded = ArtistProfileStore(directory.toFile(), PluginLog(null))
            assertEquals(image.toFile().absolutePath, reloaded.logoPath(originalArtist)?.absolutePath)
            assertNull(reloaded.logoPath("Display Artist"))
            assertEquals("Display Artist", reloaded.displayName(originalArtist))
            Files.delete(image)
            assertNull(reloaded.logoPath(originalArtist))
            assertTrue(reloaded.hasCustomLogo(originalArtist))
            reloaded.setLogo(originalArtist, null)
            val reset = ArtistProfileStore(directory.toFile(), PluginLog(null))
            assertNull(reset.logoPath(originalArtist))
            assertFalse(reset.hasCustomLogo(originalArtist))
            assertEquals("Display Artist", reset.displayName(originalArtist))
        } finally { Files.deleteIfExists(image) }
    }

    @Test
    fun editorRetainsHiddenAndAddedTracksUnderOriginalArtistIdentity() = withStore { store, directory ->
        store.setName(originalArtist, "Display Artist")
        store.toggleHidden(originalArtist, originalTrack.id)
        store.addTrack(originalArtist, addedTrack.id)

        val snapshot = store.editorSnapshot(originalModel, library)
        assertEquals(originalArtist, snapshot.name)
        assertEquals(listOf("original", "added"), snapshot.tracks.map { it.id })
        assertEquals(listOf("Original album", "Added album"), snapshot.albums.map { it.title })
        assertTrue(store.isHidden(originalArtist, originalTrack.id))
        assertEquals(listOf("added"), store.apply(originalModel, library).tracks.map { it.id })
        assertEquals("Display Artist", store.displayName(originalArtist))
        assertNull(store.displayName("Display Artist"))

        val reloaded = ArtistProfileStore(directory.toFile(), PluginLog(null))
        assertEquals(snapshot, reloaded.editorSnapshot(originalModel, library))
        assertTrue(reloaded.isHidden(originalArtist, originalTrack.id))
        assertEquals("Display Artist", reloaded.displayName(originalArtist))
    }

    @Test
    fun hiddenAddedTracksRemainRemovable() = withStore { store, _ ->
        store.addTrack(originalArtist, addedTrack.id)
        store.toggleHidden(originalArtist, addedTrack.id)
        assertEquals(listOf("original", "added"), store.editorSnapshot(originalModel, library).tracks.map { it.id })
        assertTrue(store.isAdded(originalArtist, addedTrack.id))
        assertTrue(store.isHidden(originalArtist, addedTrack.id))
        assertEquals(listOf("original"), store.apply(originalModel, library).tracks.map { it.id })

        store.removeTrack(originalArtist, addedTrack.id)
        assertEquals(originalModel, store.editorSnapshot(originalModel, library))
        assertFalse(store.isAdded(originalArtist, addedTrack.id))
        assertFalse(store.isHidden(originalArtist, addedTrack.id))
    }

    @Test
    fun aliasesReloadWithoutReplacingOriginalTrackOrAlbumKeys() = withStore { store, directory ->
        store.setName(originalArtist, "Display Artist")
        store.setTrackTitle(originalArtist, originalTrack.id, "Renamed song")
        store.setTrackTitle(originalArtist, originalTrack.id, "Renamed song")
        store.setAlbumTitle(originalArtist, "Original album", "Renamed album")

        val reloaded = ArtistProfileStore(directory.toFile(), PluginLog(null))
        val snapshot = reloaded.editorSnapshot(originalModel, library)
        assertEquals("Original song", snapshot.tracks.single().title)
        assertEquals("Original album", snapshot.albums.single().title)
        assertEquals("Renamed song", reloaded.trackTitle(snapshot.name, snapshot.tracks.single()))
        assertEquals("Renamed album", reloaded.albumTitle(snapshot.name, snapshot.albums.single().title))
        assertEquals("Display Artist", reloaded.apply(originalModel, library).name)

        reloaded.setTrackTitle(snapshot.name, snapshot.tracks.single().id, "Second song title")
        reloaded.setAlbumTitle(snapshot.name, snapshot.albums.single().title, "Second album title")
        assertEquals("Second song title", reloaded.apply(originalModel, library).tracks.single().title)
        assertEquals("Second album title", reloaded.apply(originalModel, library).albums.single().title)
        assertNull(reloaded.displayName("Display Artist"))
    }

    private fun withStore(block: (ArtistProfileStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("salt-artist-profile-test-")
        try {
            block(ArtistProfileStore(directory.toFile(), PluginLog(null)), directory)
        } finally {
            Files.deleteIfExists(directory.resolve("artist-profiles.ini"))
            Files.deleteIfExists(directory)
        }
    }

    private fun track(id: String, title: String, artist: String, album: String) = ArtistTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        path = "C:/music/$id.flac",
        genre = "",
        year = 2024,
        number = 1,
        durationMs = 120_000,
        isFavorite = false,
    )
}
