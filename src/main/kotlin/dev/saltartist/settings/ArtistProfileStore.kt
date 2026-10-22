package dev.saltartist.settings

import androidx.compose.runtime.mutableStateOf
import dev.saltartist.PluginLog
import dev.saltartist.data.ArtistAlbum
import dev.saltartist.data.ArtistPageModel
import dev.saltartist.data.ArtistTrack
import dev.saltartist.data.normalizeArtistKey
import dev.saltartist.data.splitArtistNames
import java.io.File

/**
 * 用户对某个艺人的本地覆盖：显示名、自定义头图、隐藏歌曲、歌曲/专辑改名。
 *
 * 曲库本身是只读的（Workshop API 没有写入入口），所以这些修改只作用于**插件渲染出来的页面**，
 * 不改动宿主数据库，也不会影响其它页面。删掉本文件即恢复原样。
 *
 * 存储格式刻意用行文本（`<艺人键>` 段 + `key=value`），避免在插件里引入 JSON 依赖：
 * ```
 * [bbno]
 * name=bbno$
 * photo=C:\pics\bbno.jpg
 * hide=id1,id2
 * track.id1=1-800 (Remix)
 * album.1-800=1-800 (Deluxe)
 * ```
 */
class ArtistProfileStore(
    private val pluginDir: File?,
    private val log: PluginLog,
) {
    /** 内容变化计数：页面读它来决定要不要重建 model。 */
    val revision = mutableStateOf(0)

    private val file: File? = pluginDir?.let { File(it, "artist-profiles.ini") }
    private val profiles = LinkedHashMap<String, Profile>()
    private var loaded = false

    data class Profile(
        val name: String? = null,
        val photo: String? = null,
        val hidden: Set<String> = emptySet(),
        val extra: Set<String> = emptySet(),
        val trackTitles: Map<String, String> = emptyMap(),
        val albumTitles: Map<String, String> = emptyMap(),
        val logo: String? = null,
    ) {
        val isEmpty: Boolean
            get() = name == null && photo == null && logo == null && hidden.isEmpty() && extra.isEmpty() &&
                trackTitles.isEmpty() && albumTitles.isEmpty()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            var key: String? = null
            var name: String? = null
            var photo: String? = null
            var logo: String? = null
            val hidden = mutableSetOf<String>()
            val extra = mutableSetOf<String>()
            val trackTitles = mutableMapOf<String, String>()
            val albumTitles = mutableMapOf<String, String>()

            fun flush() {
                val k = key ?: return
                if (name != null || photo != null || logo != null || hidden.isNotEmpty() || extra.isNotEmpty() ||
                    trackTitles.isNotEmpty() || albumTitles.isNotEmpty()
                ) {
                    profiles[k] = Profile(name, photo, hidden.toSet(), extra.toSet(), trackTitles.toMap(), albumTitles.toMap(), logo)
                }
            }

            f.forEachLine { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("[") && line.endsWith("]") -> {
                        flush()
                        key = line.substring(1, line.length - 1)
                        name = null; photo = null; logo = null
                        hidden.clear(); extra.clear(); trackTitles.clear(); albumTitles.clear()
                    }
                    line.startsWith("name=") -> name = line.removePrefix("name=").ifBlank { null }
                    line.startsWith("photo=") -> photo = line.removePrefix("photo=").ifBlank { null }
                    line.startsWith("logo=") -> logo = line.removePrefix("logo=").ifBlank { null }
                    line.startsWith("hide=") ->
                        hidden += line.removePrefix("hide=").split(',').map(String::trim).filter(String::isNotEmpty)
                    line.startsWith("add=") ->
                        extra += line.removePrefix("add=").split(',').map(String::trim).filter(String::isNotEmpty)
                    line.startsWith("track.") -> {
                        val rest = line.removePrefix("track.")
                        val eq = rest.indexOf('=')
                        if (eq > 0) trackTitles[rest.substring(0, eq)] = rest.substring(eq + 1)
                    }
                    line.startsWith("album.") -> {
                        val rest = line.removePrefix("album.")
                        val eq = rest.indexOf('=')
                        if (eq > 0) albumTitles[rest.substring(0, eq)] = rest.substring(eq + 1)
                    }
                }
            }
            flush()
        }.onFailure { log.log("artist profile load failed: ${log.describe(it)}") }
        log.log("artist profiles loaded=${profiles.size}")
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            pluginDir?.mkdirs()
            f.bufferedWriter().use { w ->
                profiles.forEach { (key, p) ->
                    w.write("[$key]"); w.newLine()
                    p.name?.let { w.write("name=$it"); w.newLine() }
                    p.photo?.let { w.write("photo=$it"); w.newLine() }
                    p.logo?.let { w.write("logo=$it"); w.newLine() }
                    if (p.extra.isNotEmpty()) {
                        w.write("add=" + p.extra.joinToString(",")); w.newLine()
                    }
                    if (p.hidden.isNotEmpty()) {
                        w.write("hide=" + p.hidden.joinToString(",")); w.newLine()
                    }
                    p.trackTitles.forEach { (id, title) -> w.write("track.$id=$title"); w.newLine() }
                    p.albumTitles.forEach { (title, renamed) -> w.write("album.$title=$renamed"); w.newLine() }
                }
            }
        }.onFailure { log.log("artist profile save failed: ${log.describe(it)}") }
    }

    private fun get(artistName: String): Profile? {
        ensureLoaded()
        return profiles[normalizeArtistKey(artistName)]
    }

    /** 头像/名字修改会影响「按名字找曲目」，因此都按**原始**艺人名查。 */
    fun displayName(artistName: String): String? = get(artistName)?.name
    fun photoPath(artistName: String): File? =
        get(artistName)?.photo?.let { File(it) }?.takeIf { it.isFile }

    fun logoPath(artistName: String): File? =
        get(artistName)?.logo?.let { File(it) }?.takeIf { it.isFile }

    fun hasCustomLogo(artistName: String): Boolean = get(artistName)?.logo != null

    fun trackTitle(artistName: String, track: ArtistTrack): String =
        get(artistName)?.trackTitles?.get(track.id) ?: track.title

    fun albumTitle(artistName: String, originalTitle: String): String =
        get(artistName)?.albumTitles?.get(originalTitle) ?: originalTitle

    fun isHidden(artistName: String, trackId: String): Boolean =
        get(artistName)?.hidden?.contains(trackId) == true

    fun update(artistName: String, transform: (Profile) -> Profile) {
        ensureLoaded()
        val key = normalizeArtistKey(artistName)
        val next = transform(profiles[key] ?: Profile())
        if (next.isEmpty) profiles.remove(key) else profiles[key] = next
        save()
        revision.value++
    }

    fun setName(artistName: String, value: String) =
        update(artistName) { it.copy(name = value.trim().ifBlank { null }) }

    fun setPhoto(artistName: String, file: File?) =
        update(artistName) { it.copy(photo = file?.absolutePath) }

    fun setLogo(artistName: String, file: File?) =
        update(artistName) { it.copy(logo = file?.absolutePath) }

    fun toggleHidden(artistName: String, trackId: String) = update(artistName) { p ->
        val hidden = p.hidden.toMutableSet()
        if (!hidden.remove(trackId)) hidden += trackId
        p.copy(hidden = hidden)
    }

    fun setTrackTitle(artistName: String, trackId: String, title: String) = update(artistName) { p ->
        val map = p.trackTitles.toMutableMap()
        val v = title.trim()
        if (v.isEmpty()) map.remove(trackId) else map[trackId] = v
        p.copy(trackTitles = map)
    }

    /** 往这个艺人页里「加一首本来不属于他」的曲目。 */
    fun addTrack(artistName: String, trackId: String) {
        if (trackId.isBlank()) return
        update(artistName) { p ->
            val extra = p.extra.toMutableSet()
            val hidden = p.hidden.toMutableSet()
            extra += trackId
            hidden -= trackId // 加进来的曲子不能再同时被隐藏
            p.copy(extra = extra, hidden = hidden)
        }
    }

    /** 撤销「添加」，曲子回到它自己的艺人页。 */
    fun removeTrack(artistName: String, trackId: String) = update(artistName) { p ->
        p.copy(extra = p.extra - trackId, hidden = p.hidden - trackId)
    }

    fun setAlbumTitle(artistName: String, albumTitle: String, title: String) = update(artistName) { p ->
        val map = p.albumTitles.toMutableMap()
        val v = title.trim()
        if (v.isEmpty() || v == albumTitle) map.remove(albumTitle) else map[albumTitle] = v
        p.copy(albumTitles = map)
    }

    fun reset(artistName: String) {
        ensureLoaded()
        profiles.remove(normalizeArtistKey(artistName))
        save()
        revision.value++
    }

    fun hasProfile(artistName: String): Boolean = get(artistName) != null

    /** 这首曲子是用户手动加进来的（而不是艺人本来就有）。 */
    fun isAdded(artistName: String, trackId: String): Boolean =
        get(artistName)?.extra?.contains(trackId) == true

    /**
     * 编辑器保留原始艺人键、歌曲标题与专辑键，补入手动添加的曲目，但不隐藏条目。
     * 显示别名通过 [trackTitle] / [albumTitle] 读取，避免把别名当成下一次写入的键。
     */
    fun editorSnapshot(model: ArtistPageModel, allTracks: List<ArtistTrack>): ArtistPageModel {
        val profile = get(model.name) ?: return model
        val originalIds = model.tracks.map { it.id }.toSet()
        val libraryById = allTracks.associateBy { it.id }
        val added = profile.extra.filterNot(originalIds::contains).mapNotNull(libraryById::get)
        if (added.isEmpty()) return model

        val albums = model.albums.toMutableList()
        added.forEach { track ->
            val title = track.album.ifBlank { "未命名专辑" }
            val artist = splitArtistNames(track.albumArtist.ifBlank { track.artist }).firstOrNull().orEmpty()
            val index = albums.indexOfFirst {
                normalizeArtistKey(it.title) == normalizeArtistKey(title) &&
                    normalizeArtistKey(it.artist) == normalizeArtistKey(artist)
            }
            if (index >= 0) {
                val album = albums[index]
                albums[index] = album.copy(tracks = album.tracks + track)
            } else {
                albums += ArtistAlbum(title = title, artist = artist, year = track.year, tracks = listOf(track))
            }
        }
        return model.copy(tracks = model.tracks + added, albums = albums)
    }

    /**
     * 把覆盖应用到曲库模型上：隐藏歌曲、改名、换标题，以及把用户手动「添加」进来的
     * 其它艺人的曲子并进来（`allTracks` 用来按 id 反查这些曲子）。
     */
    fun apply(model: ArtistPageModel, allTracks: List<ArtistTrack> = emptyList()): ArtistPageModel {
        val p = get(model.name) ?: return model
        val byId = if (p.extra.isEmpty() || allTracks.isEmpty()) {
            emptyMap<String, ArtistTrack>()
        } else {
            allTracks.associateBy { it.id }
        }
        val added = p.extra.mapNotNull { byId[it] }.filterNot { it.id in p.hidden }

        fun rename(t: ArtistTrack) = p.trackTitles[t.id]?.let { t.copy(title = it) } ?: t

        val baseTracks = model.tracks.filterNot { it.id in p.hidden }.map(::rename)
        val tracks = baseTracks + added.map(::rename)

        // 专辑：先按原专辑走一遍过滤/改名，再把「添加」进来的曲子各自挂到同名专辑上
        // （没有同名专辑就新建一张），这样专辑网格和计数也跟着对。
        val albums = model.albums.mapNotNull { album ->
            val kept = album.tracks
                .filterNot { it.id in p.hidden }
                .map(::rename)
            if (kept.isEmpty()) return@mapNotNull null
            val title = p.albumTitles[album.title] ?: album.title
            album.copy(title = title, tracks = kept)
        }.toMutableList()

        added.forEach { track ->
            val title = p.albumTitles[track.album] ?: track.album
            val index = albums.indexOfFirst { it.title == title }
            if (index >= 0) {
                val album = albums[index]
                albums[index] = album.copy(tracks = (album.tracks + rename(track)).distinctBy { it.id })
            } else {
                albums += ArtistAlbum(
                    title = title,
                    artist = track.albumArtist.ifBlank { track.artist },
                    year = track.year,
                    tracks = listOf(rename(track)),
                )
            }
        }

        return model.copy(
            name = p.name ?: model.name,
            tracks = tracks,
            albums = albums,
        )
    }

    /** 给编辑器用：原始曲目（不受隐藏影响）。 */
    fun rawTracks(model: ArtistPageModel): List<ArtistTrack> = model.tracks

    fun albumTitles(model: ArtistPageModel): List<ArtistAlbum> = model.albums
}
