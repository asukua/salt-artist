package dev.saltartist.settings

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import androidx.compose.runtime.mutableStateOf
import dev.saltartist.am.ArtistLanguagePolicy
import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.am.PreferredArtistLanguage
import dev.saltartist.data.normalizeArtistKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ArtistSettingsData(
    val immersiveHero: Boolean = true,
    val dimBackdrop: Boolean = true,
    val showTrackNumbers: Boolean = true,
    val preferredLanguage: PreferredArtistLanguage = PreferredArtistLanguage.CHINESE,
    val showOtherLanguages: Boolean = true,
    val favoritesFirst: Boolean = true,
    val recognizeAliases: Boolean = true,
    val artistIdentityOverrides: String = "",
) {
    val languagePolicy: ArtistLanguagePolicy
        get() = ArtistLanguagePolicy(preferredLanguage, showOtherLanguages)
    val identityPolicy = ArtistIdentityPolicy(recognizeAliases, artistIdentityOverrides)
}

/** Pure parsing also covers hand-edited or older preference files. */
internal fun artistSettingsFromValues(values: Map<String, Any?>): ArtistSettingsData = ArtistSettingsData(
    immersiveHero = values[ArtistSettings.KEY_IMMERSIVE] as? Boolean ?: true,
    dimBackdrop = values[ArtistSettings.KEY_DIM] as? Boolean ?: true,
    showTrackNumbers = values[ArtistSettings.KEY_NUMBERS] as? Boolean ?: true,
    preferredLanguage = PreferredArtistLanguage.entries.firstOrNull {
        it.name == values[ArtistSettings.KEY_LANGUAGE] || it.code == values[ArtistSettings.KEY_LANGUAGE]
    } ?: PreferredArtistLanguage.CHINESE,
    showOtherLanguages = values[ArtistSettings.KEY_OTHER_LANGUAGES] as? Boolean ?: true,
    favoritesFirst = values[ArtistSettings.KEY_FAVORITES_FIRST] as? Boolean ?: true,
    recognizeAliases = values[ArtistSettings.KEY_ALIASES] as? Boolean ?: true,
    artistIdentityOverrides = values[ArtistSettings.KEY_IDENTITY_OVERRIDES] as? String ?: "",
)

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
        val options = artistSettingsFromValues(mapOf(
            KEY_IMMERSIVE to get(KEY_IMMERSIVE, true), KEY_DIM to get(KEY_DIM, true),
            KEY_NUMBERS to get(KEY_NUMBERS, true), KEY_LANGUAGE to get(KEY_LANGUAGE, "CHINESE"),
            KEY_OTHER_LANGUAGES to get(KEY_OTHER_LANGUAGES, true), KEY_FAVORITES_FIRST to get(KEY_FAVORITES_FIRST, true),
            KEY_ALIASES to get(KEY_ALIASES, true), KEY_IDENTITY_OVERRIDES to get(KEY_IDENTITY_OVERRIDES, ""),
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
    }
}
