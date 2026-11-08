package dev.saltartist.settings

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import androidx.compose.runtime.mutableStateOf
import dev.saltartist.am.ArtistLanguagePolicy
import dev.saltartist.am.ArtistVisualPolicy
import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.am.ArtistIdentityResolver
import dev.saltartist.am.PreferredArtistLanguage
import dev.saltartist.am.artistIdentityKey
import dev.saltartist.data.normalizeArtistKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ArtistSettingsData(
    val immersiveHero: Boolean = true,
    val dimBackdrop: Boolean = true,
    val showTrackNumbers: Boolean = true,
    val preferChineseBiography: Boolean = true,
    val favoritesFirst: Boolean = true,
    val recognizeAliases: Boolean = true,
    val artistIdentityOverrides: String = "",
    val storefrontOrder: String = ArtistIdentityPolicy.DEFAULT_STOREFRONTS,
    val animatedHero: Boolean = true,
    val scrollHeroBlur: Boolean = true,
    val motionFrameRate: Int = 30,
    val motionResolution: MotionResolution = MotionResolution.FULL_HD,
    val directory: ArtistDirectoryPreferences = ArtistDirectoryPreferences(),
    val directoryBackground: DirectoryBackground = DirectoryBackground(),
) {
    val languagePolicy: ArtistLanguagePolicy
        get() = ArtistLanguagePolicy(storefronts = storefrontOrder, preferChineseBiography = preferChineseBiography)
    val visualPolicy: ArtistVisualPolicy get() = ArtistVisualPolicy(storefrontOrder, automaticLanguage = true)
    val identityPolicy = ArtistIdentityPolicy(recognizeAliases, artistIdentityOverrides, storefrontOrder)
}

/** New values win. On upgrade, keep a custom hero order ahead of the old lookup order. */
private fun migratedStorefrontOrder(values: Map<String, Any?>): String {
    fun normalized(raw: String) = ArtistVisualPolicy(raw).countries.joinToString(",")
    (values[ArtistSettings.KEY_REGION_ORDER] as? String)?.let { return normalized(it) }
    val previousHero = (values[ArtistSettings.KEY_VISUAL_STOREFRONT] as? String)?.let(::normalized)
    val previousLookup = (values[ArtistSettings.KEY_STOREFRONTS] as? String)?.let(::normalized)
    return previousHero?.takeUnless { it == ArtistVisualPolicy.DEFAULT_STOREFRONTS }
        ?: previousLookup ?: previousHero ?: ArtistVisualPolicy.DEFAULT_STOREFRONTS
}

private fun migratedChinesePreference(values: Map<String, Any?>): Boolean =
    values[ArtistSettings.KEY_PREFER_CHINESE] as? Boolean ?: run {
        val previous = PreferredArtistLanguage.entries.firstOrNull {
            it.name == values[ArtistSettings.KEY_LANGUAGE] || it.code == values[ArtistSettings.KEY_LANGUAGE]
        }
        previous == null || previous == PreferredArtistLanguage.CHINESE
    }

/** Pure parsing also covers hand-edited or older preference files. */
internal fun artistSettingsFromValues(values: Map<String, Any?>): ArtistSettingsData = ArtistSettingsData(
    immersiveHero = values[ArtistSettings.KEY_IMMERSIVE] as? Boolean ?: true,
    dimBackdrop = values[ArtistSettings.KEY_DIM] as? Boolean ?: true,
    showTrackNumbers = values[ArtistSettings.KEY_NUMBERS] as? Boolean ?: true,
    preferChineseBiography = migratedChinesePreference(values),
    favoritesFirst = values[ArtistSettings.KEY_FAVORITES_FIRST] as? Boolean ?: true,
    recognizeAliases = values[ArtistSettings.KEY_ALIASES] as? Boolean ?: true,
    artistIdentityOverrides = values[ArtistSettings.KEY_IDENTITY_OVERRIDES] as? String ?: "",
    storefrontOrder = migratedStorefrontOrder(values),
    animatedHero = values[ArtistSettings.KEY_ANIMATED_HERO] as? Boolean ?: true,
    scrollHeroBlur = values[ArtistSettings.KEY_SCROLL_BLUR] as? Boolean ?: true,
    motionFrameRate = motionFrameRate(values[ArtistSettings.KEY_MOTION_FPS]),
    motionResolution = motionResolution(values[ArtistSettings.KEY_MOTION_RESOLUTION]),
    directoryBackground = DirectoryBackground(
        mode = DirectoryBackgroundMode.entries.firstOrNull { it.name == values[ArtistSettings.KEY_DIRECTORY_BACKGROUND] } ?: DirectoryBackgroundMode.SYSTEM,
        color = (values[ArtistSettings.KEY_DIRECTORY_COLOR] as? String)?.takeIf { parseBackgroundColor(it) != null } ?: "#243448",
        imagePath = values[ArtistSettings.KEY_DIRECTORY_IMAGE] as? String ?: "",
    ),
    directory = ArtistDirectoryPreferences(
        view = ArtistDirectoryView.entries.firstOrNull { it.name == values[ArtistSettings.KEY_DIRECTORY_VIEW] } ?: ArtistDirectoryView.GRID,
        size = ArtistDirectorySize.entries.firstOrNull { it.name == values[ArtistSettings.KEY_DIRECTORY_SIZE] } ?: ArtistDirectorySize.MEDIUM,
        sort = ArtistDirectorySort.entries.firstOrNull { it.name == values[ArtistSettings.KEY_DIRECTORY_SORT] } ?: ArtistDirectorySort.NAME,
        hidden = decodeHiddenArtists(values[ArtistSettings.KEY_HIDDEN_ARTISTS] as? String ?: ""),
    ),
)

/** Replace this artist's explicit binding without rewriting unrelated entries. */
internal fun withArtistBinding(overrides: String, artistName: String, artistId: String?): String {
    require(artistName.isNotBlank() && artistName.none { it in "=;\r\n" }) { "Unsupported artist name" }
    require(artistId == null || artistId.matches(Regex("[1-9][0-9]{0,19}"))) { "Invalid Apple Music artist ID" }
    val key = artistIdentityKey(artistName)
    val otherEntries = overrides.split(Regex("[;\\r\\n]+")).map(String::trim).filter {
        it.isNotEmpty() && artistIdentityKey(it.substringBefore('=')) != key
    }
    return (otherEntries + listOfNotNull(artistId?.let { "${artistName.trim()}=$it" })).joinToString(";")
}

/**
 * 读取 preference_config.json 暴露给用户的开关。配置项缺失或类型不符时使用默认值，
 * 不抛异常——艺人页不能因为一个坏配置整页白屏。
 */
@OptIn(UnstableSpwWorkshopApi::class)
class ArtistSettings(private val config: ConfigHelper?) : AutoCloseable {
    private data class Snapshot(val options: ArtistSettingsData, val favorites: Set<String>)
    private val lock = Any()
    private val state = mutableStateOf(load())
    private var stamp = fileStamp()
    init {
        // Persist the derived values once so the host controls display the same
        // migrated choices as the plugin. Legacy keys remain available for rollback.
        val migrationStore = config
        if (migrationStore != null) {
            val missingRegions = get<Any?>(KEY_REGION_ORDER, null) == null
            val missingChinese = get<Any?>(KEY_PREFER_CHINESE, null) == null
            if (missingRegions || missingChinese) {
                val saved = runCatching {
                    if (missingRegions) migrationStore.set(KEY_REGION_ORDER, state.value.options.storefrontOrder)
                    if (missingChinese) migrationStore.set(KEY_PREFER_CHINESE, state.value.options.preferChineseBiography)
                    migrationStore.save()
                }.getOrDefault(false)
                // If reload is unavailable too, the retained keys still describe
                // the same derived choices as state; a later explicit save may
                // persist them without changing any legacy preference.
                if (!saved) runCatching { migrationStore.reload() }
                stamp = fileStamp()
            }
        }
        val oldMode = get(KEY_DIRECTORY_BACKGROUND, "SYSTEM")
        if (oldMode == "LIGHT" || oldMode == "DARK" || oldMode == "MATERIAL") {
            val saved = runCatching {
                config?.set(KEY_DIRECTORY_BACKGROUND, "SYSTEM")
                config?.save() == true
            }.getOrDefault(false)
            if (!saved) runCatching { config?.set(KEY_DIRECTORY_BACKGROUND, oldMode) }
            stamp = fileStamp()
        }
    }
    private val watcher = if (config == null) null else Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "salt-artist-settings").apply { isDaemon = true }
    }.also { service ->
        service.scheduleWithFixedDelay({
            runCatching {
                synchronized(lock) {
                    val current = fileStamp()
                    if (current != stamp) {
                        config?.reload()
                        state.value = load()
                        stamp = current
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun fileStamp(): Pair<Long, Long>? = runCatching {
        config?.getConfigPath()?.toFile()?.let { it.lastModified() to it.length() }
    }.getOrNull()

    private fun <T> get(key: String, default: T): T = runCatching { config?.get(key, default) ?: default }.getOrDefault(default)

    private fun load(): Snapshot {
        val storedFps = get<Any>(KEY_MOTION_FPS, "30")
        if (storedFps.toString() == "15") runCatching {
            config?.set(KEY_MOTION_FPS, "24")
            if (config?.save() == false) config.set(KEY_MOTION_FPS, storedFps)
        }
        val options = artistSettingsFromValues(mapOf(
            KEY_IMMERSIVE to get(KEY_IMMERSIVE, true), KEY_DIM to get(KEY_DIM, true),
            KEY_NUMBERS to get(KEY_NUMBERS, true), KEY_LANGUAGE to get(KEY_LANGUAGE, "CHINESE"),
            KEY_PREFER_CHINESE to get<Any?>(KEY_PREFER_CHINESE, null), KEY_FAVORITES_FIRST to get(KEY_FAVORITES_FIRST, true),
            KEY_REGION_ORDER to get<Any?>(KEY_REGION_ORDER, null),
            KEY_ALIASES to get(KEY_ALIASES, true), KEY_IDENTITY_OVERRIDES to get(KEY_IDENTITY_OVERRIDES, ""),
            KEY_STOREFRONTS to get(KEY_STOREFRONTS, ArtistIdentityPolicy.DEFAULT_STOREFRONTS),
            KEY_ANIMATED_HERO to get(KEY_ANIMATED_HERO, true),
            KEY_VISUAL_STOREFRONT to get(KEY_VISUAL_STOREFRONT, ArtistVisualPolicy.DEFAULT_STOREFRONTS),
            KEY_SCROLL_BLUR to get(KEY_SCROLL_BLUR, true), KEY_MOTION_FPS to storedFps,
            KEY_MOTION_RESOLUTION to get(KEY_MOTION_RESOLUTION, "FULL_HD"),
            KEY_DIRECTORY_BACKGROUND to get(KEY_DIRECTORY_BACKGROUND, "SYSTEM"),
            KEY_DIRECTORY_COLOR to get(KEY_DIRECTORY_COLOR, "#243448"), KEY_DIRECTORY_IMAGE to get(KEY_DIRECTORY_IMAGE, ""),
            KEY_DIRECTORY_VIEW to get(KEY_DIRECTORY_VIEW, "GRID"), KEY_DIRECTORY_SIZE to get(KEY_DIRECTORY_SIZE, "MEDIUM"),
            KEY_DIRECTORY_SORT to get(KEY_DIRECTORY_SORT, "NAME"), KEY_HIDDEN_ARTISTS to get(KEY_HIDDEN_ARTISTS, ""),
        ))
        val rawFavorites = runCatching { config?.get<Any?>(KEY_FAVORITES, "") as? String ?: "" }.getOrDefault("")
        val favorites = rawFavorites.split('\n').map(String::trim).filter(String::isNotEmpty).toSet()
        return Snapshot(options, favorites)
    }

    /** 本地收藏保留原始名字，匹配时规范化；不修改宿主曲库。 */
    fun favorites(): Set<String> = state.value.favorites

    fun isFavorite(artistKey: String): Boolean = favorites().any { normalizeArtistKey(it) == normalizeArtistKey(artistKey) }

    fun toggleFavorite(artistKey: String): Boolean = synchronized(lock) {
        val current = favorites().toMutableSet()
        val existing = current.firstOrNull { normalizeArtistKey(it) == normalizeArtistKey(artistKey) }
        val nowFavorite = if (existing == null) { current += artistKey; true } else { current -= existing; false }
        runCatching {
            config?.set(KEY_FAVORITES, current.joinToString("\n"))
            config?.save()
        }
        state.value = state.value.copy(favorites = current.toSet())
        stamp = fileStamp()
        nowFavorite
    }
    fun read(): ArtistSettingsData = state.value.options

    fun artistBinding(artistName: String): String? =
        ArtistIdentityResolver.parseOverrides(read().artistIdentityOverrides).ids[artistIdentityKey(artistName)]

    /** A manual selection is explicit consent to enable the matching policy. */
    fun setArtistBinding(artistName: String, artistId: String?): Boolean = synchronized(lock) {
        val store = config ?: return@synchronized false
        val previous = state.value.options
        val updated = runCatching { withArtistBinding(previous.artistIdentityOverrides, artistName, artistId) }
            .getOrNull() ?: return@synchronized false
        val enabled = if (artistId != null) true else previous.recognizeAliases
        val saved = runCatching {
            store.set(KEY_IDENTITY_OVERRIDES, updated)
            store.set(KEY_ALIASES, enabled)
            store.save()
        }.getOrDefault(false)
        if (saved) {
            state.value = state.value.copy(options = previous.copy(artistIdentityOverrides = updated, recognizeAliases = enabled))
            stamp = fileStamp()
        } else {
            runCatching {
                store.set(KEY_IDENTITY_OVERRIDES, previous.artistIdentityOverrides)
                store.set(KEY_ALIASES, previous.recognizeAliases)
            }
        }
        saved
    }

    fun setDirectoryBackground(background: DirectoryBackground): Boolean = synchronized(lock) {
        val store = config ?: return@synchronized false
        if (parseBackgroundColor(background.color) == null) return@synchronized false
        val previous = read().directoryBackground
        fun write(value: DirectoryBackground) {
            store.set(KEY_DIRECTORY_BACKGROUND, value.mode.name)
            store.set(KEY_DIRECTORY_COLOR, value.color)
            store.set(KEY_DIRECTORY_IMAGE, value.imagePath)
        }
        val saved = runCatching { write(background); store.save() }.getOrDefault(false)
        if (saved) {
            state.value = state.value.copy(options = read().copy(directoryBackground = background))
            stamp = fileStamp()
        } else runCatching { write(previous) }
        saved
    }

    /** Publish only persisted directory choices; a failed save leaves the active view intact. */
    fun updateDirectory(transform: (ArtistDirectoryPreferences) -> ArtistDirectoryPreferences): Boolean = synchronized(lock) {
        val store = config ?: return@synchronized false
        val previous = read().directory
        val updated = transform(previous)
        fun write(value: ArtistDirectoryPreferences) {
            store.set(KEY_DIRECTORY_VIEW, value.view.name)
            store.set(KEY_DIRECTORY_SIZE, value.size.name)
            store.set(KEY_DIRECTORY_SORT, value.sort.name)
            store.set(KEY_HIDDEN_ARTISTS, encodeHiddenArtists(value.hidden))
        }
        val saved = runCatching { write(updated); store.save() }.getOrDefault(false)
        if (saved) {
            state.value = state.value.copy(options = read().copy(directory = updated))
            stamp = fileStamp()
        } else runCatching { write(previous) }
        saved
    }

    override fun close() { watcher?.shutdownNow() }

    companion object {
        const val KEY_IMMERSIVE = "immersive_hero"
        const val KEY_DIM = "dim_backdrop"
        const val KEY_NUMBERS = "show_track_numbers"
        const val KEY_FAVORITES = "favorite_artists"
        const val KEY_LANGUAGE = "preferred_artist_language"
        const val KEY_OTHER_LANGUAGES = "show_other_artist_languages"
        const val KEY_FAVORITES_FIRST = "favorite_artists_first"
        const val KEY_ALIASES = "recognize_artist_aliases"
        const val KEY_IDENTITY_OVERRIDES = "artist_identity_overrides"
        const val KEY_STOREFRONTS = "apple_music_storefronts"
        const val KEY_REGION_ORDER = "artist_storefront_order"
        const val KEY_PREFER_CHINESE = "prefer_simplified_chinese_biography"
        const val KEY_ANIMATED_HERO = "animated_artist_hero"
        const val KEY_VISUAL_STOREFRONT = "artist_visual_storefront"
        const val KEY_VISUAL_LANGUAGE = "artist_visual_language"
        const val KEY_SCROLL_BLUR = "scroll_artist_hero_blur"
        const val KEY_MOTION_FPS = "artist_motion_fps"
        const val KEY_MOTION_RESOLUTION = "artist_motion_resolution"
        const val KEY_DIRECTORY_BACKGROUND = "directory_background"
        const val KEY_DIRECTORY_COLOR = "directory_background_color"
        const val KEY_DIRECTORY_IMAGE = "directory_background_image"
        const val KEY_DIRECTORY_VIEW = "artist_directory_view"
        const val KEY_DIRECTORY_SIZE = "artist_directory_size"
        const val KEY_DIRECTORY_SORT = "artist_directory_sort"
        const val KEY_HIDDEN_ARTISTS = "hidden_artist_names"
    }
}
