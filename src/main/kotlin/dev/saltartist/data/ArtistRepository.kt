package dev.saltartist.data

import androidx.compose.runtime.mutableStateOf
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltartist.PluginLog
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * 曲库索引。Workshop Library 只提供曲目（没有艺人实体），所以艺人页的数据视图
 * 全部由插件自己聚合：
 *  - 艺人匹配用「规范化后的完整名字相等」，而不是 contains，避免 “Eminem” 命中
 *    “Eminem Tribute Band”；
 *  - 一张专辑按 (专辑名, 专辑艺人) 归并，避免同名专辑合并；
 *  - 没有播放热度数据，「热门歌曲」按 专辑年份 → 曲序 → 时长 排序，并如实叫「歌曲」。
 */
class ArtistRepository(private val log: PluginLog) {
    private val tracksState = mutableStateOf<List<ArtistTrack>?>(null)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicInteger()
    private data class CachedModel(val source: List<ArtistTrack>, val model: ArtistPageModel)
    private val modelCache = ConcurrentHashMap<String, CachedModel>()

    val tracks: List<ArtistTrack>? get() = tracksState.value

    /** 全库曲目（编辑器「添加歌曲」的搜索来源）。 */
    val allTracks: List<ArtistTrack> get() = tracksState.value.orEmpty()
    val isLoaded: Boolean get() = tracksState.value != null

    fun ensureLoaded() {
        if (tracksState.value != null) return
        if (!loading.compareAndSet(false, true)) return
        val generation = loadGeneration.get()
        runCatching {
            val library = WorkshopApi.library
            library.getAllTracks().whenComplete { items, error ->
                if (generation != loadGeneration.get()) return@whenComplete
                loading.set(false)
                if (error != null) {
                    log.log("library load failed: ${log.describe(error)}")
                    return@whenComplete
                }
                val mapped = (items ?: emptyList()).map { toTrack(it, 0L) }
                log.log("library loaded tracks=${mapped.size} added-time metadata pending")
                tracksState.value = mapped
                // MediaItem drops addedTime. Read the same API implementation's raw
                // Track query in the background; RepoService.snapshotTracks is not
                // guaranteed to contain the initial library at plugin startup.
                queryHostLibraryAddedTimes(library).orTimeout(8, TimeUnit.SECONDS)
                    .whenComplete metadata@{ addedTimes, metadataError ->
                        if (generation != loadGeneration.get() || tracksState.value !== mapped) return@metadata
                        if (metadataError != null) {
                            log.log("library added-time unavailable: ${metadataError.javaClass.simpleName}")
                            return@metadata
                        }
                        val enriched = mapped.map { track ->
                            track.copy(libraryAddedTime = addedTimes?.get(track.id) ?: 0L)
                        }
                        val known = enriched.count { it.libraryAddedTime > 0L }
                        log.log("library raw added-time tracks=${mapped.size} added-time-known=$known")
                        if (known > 0) {
                            modelCache.clear()
                            tracksState.value = enriched
                        }
                    }
            }
        }.onFailure {
            if (generation != loadGeneration.get()) return@onFailure
            loading.set(false)
            log.log("library load threw: ${log.describe(it)}")
        }
    }

    fun invalidate() {
        loadGeneration.incrementAndGet()
        modelCache.clear()
        tracksState.value = null
        loading.set(false)
    }

    fun model(artistName: String): ArtistPageModel {
        ensureLoaded()
        // The first composition may precede the asynchronous library response.
        // Do not cache that transient empty page or it would outlive the load.
        val all = tracksState.value ?: return ArtistPageModel(
            name = artistName,
            tracks = emptyList(),
            albums = emptyList(),
            genres = emptyList(),
            yearRange = null,
        )
        val key = normalizeArtistKey(artistName)
        modelCache[key]?.takeIf { it.source === all }?.let { return it.model }

        val matched = all.filter { track -> matchKeys(track).contains(key) }
        val sorted = matched.sortedWith(
            compareByDescending<ArtistTrack> { it.year }
                .thenBy { it.number }
                .thenBy { it.title.lowercase() },
        )
        val albums = sorted
            .groupBy { normalizeArtistKey(it.album) + "|" + normalizeArtistKey(it.albumArtist.ifBlank { it.artist }) }
            .map { (_, list) ->
                val ordered = list.sortedWith(compareBy({ it.number }, { it.title.lowercase() }))
                val first = ordered.first()
                ArtistAlbum(
                    title = first.album.ifBlank { "未命名专辑" },
                    // 宿主专辑路由按「专辑名 + 专辑艺人」定位；多位艺人合并串（"A/B"）
                    // 会让宿主匹配不到专辑、渲染出只有返回箭头的空白页，因此取首位艺人。
                    artist = albumArtistOf(first),
                    year = ordered.minOf { it.year },
                    tracks = ordered,
                )
            }
            .sortedWith(compareByDescending<ArtistAlbum> { it.year }.thenBy { it.title.lowercase() })

        val years = matched.map { it.year }.filter { it > 0 }
        val model = ArtistPageModel(
            name = artistName,
            tracks = sorted,
            albums = albums,
            genres = matched.map { it.genre }.filter { it.isNotEmpty() }.distinct(),
            yearRange = if (years.isEmpty()) null else years.min()..years.max(),
        )
        modelCache[key] = CachedModel(all, model)
        return model
    }

    private fun albumArtistOf(track: ArtistTrack): String {
        val albumArtist = trainArtist(track.albumArtist)
        if (albumArtist.isNotEmpty()) return albumArtist
        return trainArtist(track.artist)
    }

    /** 取单个艺人名：优先 albumArtist，其次 artist；多值串取首位。 */
    private fun trainArtist(value: String): String =
        if (value.isBlank()) "" else (splitArtistNames(value).firstOrNull() ?: value.trim())

    private fun matchKeys(track: ArtistTrack): Set<String> {
        val keys = HashSet<String>(4)
        splitArtistNames(track.artist).forEach { keys.add(normalizeArtistKey(it)) }
        if (track.albumArtist.isNotBlank()) {
            splitArtistNames(track.albumArtist).forEach { keys.add(normalizeArtistKey(it)) }
        }
        return keys
    }

    private fun toTrack(item: MediaItem, libraryAddedTime: Long): ArtistTrack = ArtistTrack(
        id = item.id,
        title = item.title.ifBlank { "未知曲目" },
        artist = item.artist,
        album = item.album,
        albumArtist = item.albumArtist,
        path = item.path,
        genre = item.genre,
        year = item.year,
        number = item.number,
        durationMs = item.duration,
        isFavorite = item.isFavorite,
        libraryAddedTime = libraryAddedTime,
    )
}

internal fun queryHostLibraryAddedTimes(library: Any): CompletableFuture<Map<String, Long>> {
    val future = CompletableFuture<Map<String, Long>>()
    CompletableFuture.runAsync {
        runCatching {
            // 1.19.1 bytecode: the Library implementation's sole public static
            // Function1 accessor returns its injected suspend getAllTracks query.
            // Select its signature, never a version-dependent obfuscated name.
            val accessor = library.javaClass.methods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.parameterCount == 1 &&
                    method.parameterTypes[0] == library.javaClass &&
                    method.returnType.name == "kotlin.jvm.functions.Function1"
            } ?: error("Raw library query accessor unavailable")
            @Suppress("UNCHECKED_CAST")
            val query = accessor.invoke(null, library) as? ((Continuation<Any?>) -> Any?)
                ?: error("Raw library query unavailable")
            fun complete(value: Any?) {
                val rawTracks = value as? Iterable<*> ?: error("Raw library query did not return tracks")
                future.complete(libraryAddedTimesFromHostSnapshot(rawTracks))
            }
            val continuation = object : Continuation<Any?> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Any?>) {
                    result.fold(
                        onSuccess = { raw -> runCatching { complete(raw) }.onFailure(future::completeExceptionally) },
                        onFailure = future::completeExceptionally,
                    )
                }
            }
            val immediate = query(continuation)
            if (immediate !== COROUTINE_SUSPENDED) complete(immediate)
        }.onFailure(future::completeExceptionally)
    }
    return future
}

/**
 * 1.19.1 已核实：宿主 raw Track 查询结果 -> Track.getId()/getAddedTime()。
 * addedTime 沿用宿主语义（文件创建时间代理）；缺失、不匹配或不可读时返回未知，
 * 不读文件 mtime，也不把插件编辑/首次观察时刻当成入库时刻。
 */
internal fun libraryAddedTimesFromHostSnapshot(snapshot: Iterable<*>): Map<String, Long> {
    val addedTimes = HashMap<String, Long>()
    snapshot.forEach { track ->
        if (track == null) return@forEach
        runCatching {
            val id = track.javaClass.getMethod("getId").invoke(track) as? String
            val time = track.javaClass.getMethod("getAddedTime").invoke(track) as? Long
            if (!id.isNullOrBlank() && time != null && time > 0L) {
                addedTimes[id] = maxOf(addedTimes[id] ?: 0L, time)
            }
        }
    }
    return addedTimes
}
