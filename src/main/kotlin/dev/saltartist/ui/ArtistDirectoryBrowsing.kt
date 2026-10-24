package dev.saltartist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.saltartist.bridge.DirectoryArtist
import dev.saltartist.settings.ArtistDirectoryPreferences
import dev.saltartist.settings.ArtistDirectorySize
import dev.saltartist.settings.ArtistDirectorySort
import dev.saltartist.settings.ArtistDirectoryView

internal data class DirectoryScrollKey(val view: ArtistDirectoryView, val size: ArtistDirectorySize,
                                      val sort: ArtistDirectorySort, val query: String) {
    constructor(prefs: ArtistDirectoryPreferences, query: String) : this(prefs.view, prefs.size, prefs.sort, query)
}

internal data class DirectoryScrollPosition(val index: Int = 0, val offset: Int = 0)

/** Session-only navigation memory; never retains a lazy layout, UI owner or native ViewModel. */
internal class ArtistDirectoryBrowsing {
    var query by mutableStateOf("")
    var nativeInterface by mutableStateOf(false)
    private var key: DirectoryScrollKey? = null
    private var position = DirectoryScrollPosition()
    private var anchor: String? = null

    fun save(layout: DirectoryScrollKey, artists: List<DirectoryArtist>, index: Int, offset: Int) {
        // Loading/empty content must not overwrite a useful return position.
        if (artists.isEmpty()) return
        key = layout
        val boundedIndex = index.coerceIn(0, artists.lastIndex)
        position = DirectoryScrollPosition(boundedIndex, offset.coerceAtLeast(0))
        anchor = artists[boundedIndex].name
    }

    fun restore(layout: DirectoryScrollKey, artists: List<DirectoryArtist>): DirectoryScrollPosition {
        if (layout != key || artists.isEmpty()) return DirectoryScrollPosition()
        val anchored = artists.indexOfFirst { it.name == anchor }
        return position.copy(index = if (anchored >= 0) anchored else position.index.coerceAtMost(artists.lastIndex))
    }
}
