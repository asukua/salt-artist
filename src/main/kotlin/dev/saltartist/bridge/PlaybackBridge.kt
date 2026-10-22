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
 * 插件只负责把本地曲库里的歌曲喂进队列。任何一步失败都返回 false，调用方回退到
 * 「不改变播放状态」，不会把宿主搞进异常状态。
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

        val withIndex = setQueueWithIndex
        if (withIndex != null) {
            val ok = runCatching { withIndex.invoke(controller, playable, targetIndex) }.isSuccess
            log.log("playback setPlaybackQueue(list=${playable.size}, index=$targetIndex) ok=$ok")
            if (ok) {
                resume()
                return true
            }
        }
        val single = setQueueSingle
        val first = playable.getOrNull(targetIndex)
        if (single != null && first != null) {
            val ok = runCatching { single.invoke(controller, first) }.isSuccess
            log.log("playback setPlaybackQueue(item) ok=$ok")
            if (ok) resume()
            return ok
        }
        return false
    }

    /**
     * 队列写入后补一次 play()：宿主在某些状态下只入队不起播，底部迷你播放器也就不会出现。
     * 已经在播放时这次调用是幂等的。
     */
    private fun resume() {
        val ok = runCatching { WorkshopApi.playback.play() }.isSuccess
        log.logOnce("playback-resume", "playback resume() ok=$ok")
    }

    private companion object {
        const val CONTROLLER_CLASS = "com.xuncorp.voxzen.service.PlaybackController"
        const val ITEM_CLASS = "com.xuncorp.pisces.PiscesMediaItem"
    }
}
