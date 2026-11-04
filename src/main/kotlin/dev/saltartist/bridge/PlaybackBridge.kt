package dev.saltartist.bridge

import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltartist.PluginLog
import dev.saltartist.data.ArtistTrack
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Workshop API dev23 没有「按歌曲 ID 播放」的公开入口，只有 play/pause/next/previous。
 * 宿主自身的播放队列在 `PlaybackController`（Kotlin object）上，队列元素是
 * `com.xuncorp.pisces.PiscesMediaItem(id, title, artist, album, albumArtist, path)`。
 *
 * 这里用反射复用宿主播放控制器：队列、播放状态、底部迷你播放器、续播都由宿主维护，
 * 插件只负责按宿主顺序提交队列、准备和播放命令。同步调用失败时返回 false；
 * 命令的实际执行与播放状态由宿主异步处理。
 */
class PlaybackBridge(private val log: PluginLog) {
    private val controller: Any? by lazy {
        HostReflection.singletonOrNull(CONTROLLER_CLASS).also {
            log.logOnce("playback-controller", "PlaybackController available=${it != null}")
        }
    }

    private val itemConstructor: Constructor<*>? by lazy {
        HostReflection.classOrNull(ITEM_CLASS)
            ?.constructors
            ?.firstOrNull { ctor -> ctor.parameterCount == 6 && ctor.parameterTypes.all { it == String::class.java } }
            ?.apply { isAccessible = true }
            .also { log.logOnce("playback-item", "PiscesMediaItem constructor=${it != null}") }
    }

    private val setQueueWithIndex: Method? by lazy {
        val type = controller?.javaClass ?: return@lazy null
        runCatching {
            type.methods.firstOrNull { method ->
                method.name == "setPlaybackQueue" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0].isAssignableFrom(ArrayList::class.java) &&
                    method.parameterTypes[1] == Integer.TYPE
            }
        }.getOrNull().also { method ->
            log.logOnce("playback-setqueue", "setPlaybackQueue(List, int)=${method != null}")
        }
    }

    private val setQueueSingle: Method? by lazy {
        val type = controller?.javaClass ?: return@lazy null
        runCatching {
            type.methods.firstOrNull { method ->
                method.name == "setPlaybackQueue" &&
                    method.parameterCount == 1 &&
                    itemConstructor?.declaringClass?.isAssignableFrom(method.parameterTypes[0]) == true
            }
        }.getOrNull()
    }

    val isAvailable: Boolean
        get() = controller != null && itemConstructor != null && (setQueueWithIndex != null || setQueueSingle != null)

    /**
     * 用给定曲目覆盖宿主播放队列并从 [startIndex] 开始播放。
     * [tracks] 中缺少可播放路径的条目会被跳过，起播位置按跳过数量修正。
     */
    fun play(tracks: List<ArtistTrack>, startIndex: Int, shuffle: Boolean = false): Boolean {
        if (tracks.isEmpty()) return false
        val controller = controller ?: return false
        val ctor = itemConstructor ?: return false

        val ordered = if (shuffle) tracks.shuffled() else tracks
        val anchorId = ordered.getOrNull(startIndex)?.id
        val playable = ArrayList<Any?>(ordered.size)
        var targetIndex = 0
        ordered.forEachIndexed { index, track ->
            val item = runCatching {
                ctor.newInstance(track.id, track.title, track.artist, track.album, track.albumArtist, track.path)
            }.getOrNull()
            if (item != null && track.path.isNotBlank()) {
                if (track.id == anchorId) targetIndex = playable.size
                playable.add(item)
            }
        }
        if (playable.isEmpty()) {
            log.log("playback skipped: no playable track for request")
            return false
        }

        // Match SPW's own playback actions: queue -> prepare -> play.
        // All three commands are submitted in order to the host service.
        val prepare = HostReflection.noArgMethod(controller, "prepare")
        if (prepare == null) {
            log.log("playback prepare unavailable")
            return false
        }
        return submitPlayback(
            enqueue = {
                val listOk = setQueueWithIndex?.let { method ->
                    runCatching { method.invoke(controller, playable, targetIndex) }.isSuccess
                } ?: false
                log.log("playback setPlaybackQueue(list=${playable.size}, index=$targetIndex) ok=$listOk")
                if (listOk) true else {
                    val single = setQueueSingle
                    val first = playable.getOrNull(targetIndex)
                    val singleOk = single != null && first != null &&
                        runCatching { single.invoke(controller, first) }.isSuccess
                    log.log("playback setPlaybackQueue(item) ok=$singleOk")
                    singleOk
                }
            },
            prepare = {
                runCatching { prepare.invoke(controller) }.isSuccess.also {
                    log.log("playback prepare() submitted=$it")
                }
            },
            play = {
                runCatching { WorkshopApi.playback.play() }.isSuccess.also {
                    log.log("playback play() submitted=$it")
                }
            },
        )
    }

    private companion object {
        const val CONTROLLER_CLASS = "com.xuncorp.voxzen.service.PlaybackController"
        const val ITEM_CLASS = "com.xuncorp.pisces.PiscesMediaItem"
    }
}

/** Queue, prepare and play must be submitted in the same order as the native host. */
internal fun submitPlayback(enqueue: () -> Boolean, prepare: () -> Boolean, play: () -> Boolean): Boolean {
    if (!enqueue()) return false
    if (!prepare()) return false
    return play()
}
