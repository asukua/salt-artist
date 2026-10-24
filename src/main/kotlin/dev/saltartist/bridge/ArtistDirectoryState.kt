package dev.saltartist.bridge

import androidx.compose.runtime.mutableStateOf
import dev.saltartist.PluginLog
import dev.saltartist.am.artistIdentityKey
import dev.saltartist.data.ArtistTrack
import dev.saltartist.data.splitArtistNames
import dev.saltartist.settings.ArtistDirectoryPreferences
import dev.saltartist.settings.ArtistDirectorySort
import java.lang.reflect.Modifier
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

internal data class DirectoryArtist(val name: String, val coverPath: String? = null, val favorite: Boolean = false)
internal data class DirectoryStats(val tracks: Int = 0, val albums: Int = 0, val added: Long = 0, val coverId: String = "")

/** Read the native ready-state groups; never infer or overwrite the host artist list. */
internal class ArtistDirectoryState(private val log: PluginLog) {
    private val state = mutableStateOf<List<DirectoryArtist>?>(null)
    val artists: List<DirectoryArtist>? get() = state.value
    private var lastState: Any? = null
    private val observed = mutableStateOf<StateFlow<*>?>(null)
    val flow: StateFlow<*>? get() = observed.value

    fun observe(value: Any?) {
        if (value is StateFlow<*>) observed.value = value
        else detach()
    }

    fun detach() { observed.value = null; reset() }

    fun capture(ready: Any?) {
        if (ready == null || ready === lastState) return
        runCatching { directoryArtistsFromState(ready) }.onSuccess {
            lastState = ready
            state.value = it
            log.logOnce("directory-020-ready", "directory controls ready artists=${it.size}")
        }.onFailure { reset(); log.logOnce("directory-020-state", "directory kept native UI: ${log.describe(it)}") }
    }

    fun reset() { lastState = null; state.value = null }
}

internal fun directoryArtistsFromState(ready: Any): List<DirectoryArtist> {
    fun listFrom(target: Any): List<*> {
        // Kotlin data classes expose both getter and componentN with the same
        // return type. The verified ready state and group each own one List field.
        val field = target.javaClass.declaredFields.single {
            !Modifier.isStatic(it.modifiers) && it.type == List::class.java
        }.apply { isAccessible = true }
        return field.get(target) as List<*>
    }
    val groups = listFrom(ready)
    require(groups.size <= 100_000)
    val names = groups.flatMap { listFrom(checkNotNull(it)) }.map {
        val name = HostReflection.stringProperty(it, "getName") ?: error("Missing artist name")
        DirectoryArtist(name, HostReflection.stringProperty(it, "getCover"),
            runCatching { it?.javaClass?.getMethod("isFavorite")?.invoke(it) as? Boolean }.getOrNull() == true)
    }
    require(names.size <= 100_000 && names.map { it.name }.distinct().size == names.size)
    return names
}

internal fun directoryStatistics(tracks: List<ArtistTrack>): Map<String, DirectoryStats> {
    val grouped = linkedMapOf<String, MutableMap<String, ArtistTrack>>()
    for (track in tracks) {
        val names = listOf(track.artist, track.albumArtist).flatMap { listOf(it) + splitArtistNames(it) }
            .filter(String::isNotBlank).map(::artistIdentityKey).distinct()
        for (name in names) grouped.getOrPut(name) { linkedMapOf() }[track.id] = track
    }
    return grouped.mapValues { (_, source) ->
        val items = source.values
        DirectoryStats(items.size, items.map { it.album to it.albumArtist.ifBlank { it.artist } }.distinct().size,
            items.maxOfOrNull { it.libraryAddedTime.coerceAtLeast(0) } ?: 0,
            items.firstOrNull()?.id.orEmpty())
    }
}

internal fun orderDirectoryArtists(
    source: List<DirectoryArtist>, preferences: ArtistDirectoryPreferences,
    stats: Map<String, DirectoryStats>, favorites: Set<String>, favoritesFirst: Boolean, query: String,
): List<DirectoryArtist> {
    val favoriteKeys = favorites.mapTo(hashSetOf(), ::artistIdentityKey)
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    val names = Comparator<DirectoryArtist> { a, b -> collator.compare(a.name, b.name) }
    val comparator = when (preferences.sort) {
        ArtistDirectorySort.NAME -> names
        ArtistDirectorySort.NAME_DESC -> names.reversed()
        ArtistDirectorySort.ADDED -> compareByDescending<DirectoryArtist> { stats[artistIdentityKey(it.name)]?.added ?: 0L }.then(names)
        ArtistDirectorySort.TRACKS -> compareByDescending<DirectoryArtist> { stats[artistIdentityKey(it.name)]?.tracks ?: 0 }.then(names)
        ArtistDirectorySort.ALBUMS -> compareByDescending<DirectoryArtist> { stats[artistIdentityKey(it.name)]?.albums ?: 0 }.then(names)
    }
    val filtered = source.filter { !preferences.isHidden(it.name) && it.name.contains(query.trim(), ignoreCase = true) }
    val sorted = filtered.sortedWith(if (favoritesFirst)
        compareBy<DirectoryArtist> { !it.favorite && artistIdentityKey(it.name) !in favoriteKeys }.then(comparator) else comparator)
    return sorted
}
