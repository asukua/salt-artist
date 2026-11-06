package dev.saltartist.ui

import dev.saltartist.PluginLog
import dev.saltartist.artwork.ArtworkStore
import dev.saltartist.bridge.NavigationBridge
import dev.saltartist.bridge.PlaybackBridge
import dev.saltartist.data.ArtistRepository
import dev.saltartist.settings.ArtistSettings
import dev.saltartist.settings.ArtistProfileStore
import dev.saltartist.hook.HookTargets
import dev.saltartist.bridge.nativeLibraryArtistNames
import androidx.compose.runtime.mutableStateOf
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/**
 * 页面依赖集合。插件生命周期内只创建一次，Hook 的 @Composable 内容通过它拿到
 * 曲库索引、图片缓存、播放/导航桥和配置，避免在组合内部做反射或 IO。
 */
class ArtistScreenDeps(
    val repository: ArtistRepository,
    val artwork: ArtworkStore,
    val playback: PlaybackBridge,
    val navigation: NavigationBridge,
    val settings: ArtistSettings,
    val profiles: ArtistProfileStore,
    val log: PluginLog,
    val directoryTargets: HookTargets.DirectoryTargets? = null,
) {
    internal val directory = dev.saltartist.bridge.ArtistDirectoryState(log)
    internal val directoryBrowsing = ArtistDirectoryBrowsing()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadPreparation: Job? = null
    val preparingDownloads = mutableStateOf(false)

    @Synchronized
    fun downloadAllArtists() {
        if (preparingDownloads.value || artwork.batchDownloads.progress.running) return
        val target = directoryTargets ?: return
        val options = settings.read()
        preparingDownloads.value = true
        downloadPreparation = backgroundScope.launch {
            try {
                check(WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.LIBRARY_READ)) {
                    "Library read permission is required"
                }
                val artists = nativeLibraryArtistNames(target)
                val started = synchronized(this@ArtistScreenDeps) {
                    coroutineContext.ensureActive()
                    artwork.batchDownloads.start(artists) { name ->
                        artwork.prefetchArtist(name, options.languagePolicy, options.identityPolicy, options.visualPolicy,
                            options.motionResolution)
                    }
                }
                if (!started) runCatching { WorkshopApi.ui.toast("曲库中没有可下载的艺人", WorkshopApi.Ui.ToastType.Warning) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.log("download all artists could not read native directory: ${log.describe(error)}")
                runCatching { WorkshopApi.ui.toast("无法读取完整艺人列表，请稍后重试", WorkshopApi.Ui.ToastType.Warning) }
            } finally { preparingDownloads.value = false }
        }
    }

    @Synchronized
    fun cancelArtistDownloads() {
        downloadPreparation?.cancel()
        artwork.batchDownloads.cancel()
    }

    fun close() {
        backgroundScope.cancel()
        artwork.shutdown()
        settings.close()
    }
}
