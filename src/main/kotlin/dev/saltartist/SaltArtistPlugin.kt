package dev.saltartist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import androidx.compose.ui.graphics.Color
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.hook.HookHandle
import dev.saltartist.am.AppleMusicCatalog
import dev.saltartist.artwork.ArtworkStore
import dev.saltartist.artwork.ArtistMotionPlayers
import dev.saltartist.bridge.HostReflection
import dev.saltartist.bridge.ArtistDirectoryBridge
import dev.saltartist.bridge.NavigationBridge
import dev.saltartist.bridge.PlaybackBridge
import dev.saltartist.data.ArtistRepository
import dev.saltartist.hook.HookTargets
import dev.saltartist.settings.ArtistProfileStore
import dev.saltartist.settings.ArtistSettings
import dev.saltartist.ui.ArtistScreen
import dev.saltartist.ui.ArtistDirectoryAvatar
import dev.saltartist.ui.ArtistScreenDeps
import dev.saltartist.ui.SaltText
import java.io.File

/**
 * 插件入口。
 *
 * 职责只有三件事：
 *  1. 准备运行时依赖（曲库索引、图片缓存、播放/导航桥、配置）；
 *  2. 按已核实的宿主版本，在艺人页 Composable 的唯一调用点覆盖 [ArtistScreen]；
 *  3. 停用/卸载时释放句柄与线程池，恢复宿主原生艺人页。
 *
 * 未核实的版本不注册 Hook；目标混淆名按版本选择，宿主继续保留原生回退。
 */
@OptIn(UnstableSpwWorkshopApi::class)
class SaltArtistPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    private val hostVersion: String = pluginContext.spwVersion.substringBefore('-')
    private var log: PluginLog = PluginLog(null)
    private var config: ConfigHelper? = null
    private var deps: ArtistScreenDeps? = null
    private var hookHandle: HookHandle? = null
    private var avatarHandles: List<HookHandle> = emptyList()
    private var directoryHandles: List<HookHandle> = emptyList()

    override fun start() {
        config = runCatching { WorkshopApi.manager.createConfigManager().getConfig(CONFIG_FILE) }.getOrNull()
        log = PluginLog(logPath())
        log.log("start plugin=${pluginContext.pluginId} version=${pluginContext.pluginVersion}")
        log.log("host=${pluginContext.spwVersion} channel=${pluginContext.spwChannel} path=${pluginContext.pluginPath}")

        val targets = HookTargets.forHost(pluginContext.spwVersion)
        if (targets == null) {
            val supported = HookTargets.supportedHostVersions.joinToString(" / ")
            log.log("host $hostVersion not supported, hooks skipped (verified $supported)")
            toast("Salt Artist 已核实兼容 Salt Player $supported，当前 $hostVersion")
            return
        }
        log.log("host profile=$hostVersion page=${targets.artistPageClass} viewModel=${targets.artistViewModelClass} avatar=${targets.artistAvatarClass}")
        ArtistMotionPlayers.begin()
        // 权限状态只记录不拦截：真正的判定交给 afterComposable 抛出的
        // PluginPermissionDeniedException，这样宿主策略变化时日志能给出准确原因。
        val classTransform = runCatching {
            WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.CLASS_TRANSFORM)
        }.getOrElse { false }
        val libraryRead = runCatching {
            WorkshopApi.manager.isPermissionGranted(com.xuncorp.spw.workshop.api.PluginPermission.LIBRARY_READ)
        }.getOrElse { false }
        log.log("permissions class-transform=$classTransform library-read=$libraryRead")

        val pluginDir = resolvePluginDir()
        val catalog = AppleMusicCatalog(pluginDir, log)
        val store = ArtworkStore(pluginDir, log, catalog)
        val screenDeps = ArtistScreenDeps(
            repository = ArtistRepository(log),
            artwork = store,
            playback = PlaybackBridge(log),
            navigation = NavigationBridge(log),
            settings = ArtistSettings(config),
            profiles = ArtistProfileStore(pluginDir, log),
            log = log,
        )
        deps = screenDeps
        screenDeps.repository.ensureLoaded()

        hookHandle = runCatching {
            // 用 afterComposable 在同一调用点追加内容：宿主原生艺人页先绘制，我们的整页
            // 面板随后以不透明背景覆盖其上。replaceComposable 在该调用点实测不生效
            // （注册成功但内容从不组合），after 语义则确认会被调用，因此这里以覆盖
            // 的方式实现"整页替换"的视觉效果，同时避免依赖未生效的替换语义。
            WorkshopApi.hookRegistrar.afterComposable(
                className = targets.artistPageClass,
                methodName = HookTargets.ARTIST_PAGE_METHOD,
                inClass = HookTargets.ARTIST_PAGE_CALLSITE,
                parameterTypes = targets.artistPageParams,
                priority = 100,
            ) { call ->
                val artistName = resolveArtistName(call.arguments.firstOrNull())
                ArtistPageHost(artistName, screenDeps)
            }
        }.onFailure {
            log.log("afterComposable failed: ${it.javaClass.name}: ${it.message}")
            log.log("afterComposable stack: ${log.describe(it)}")
            toast("Salt Artist 无法接入当前宿主界面：${it.javaClass.simpleName}")
        }.getOrNull()

        log.log("hook installed=${hookHandle != null}")
        if (hookHandle == null) {
            // 主覆盖层失败时保留完整原生页，不能继续单独隐藏它的头像。
            store.shutdown()
            screenDeps.settings.close()
            deps = null
            return
        }

        // 宿主在切换到艺人页时会做一次入场动画：这期间我们的覆盖层是跟着淡入的，
        // 底下原生页的圆形头像会透出来闪一下。整页都被我们盖住后那个头像永远看不见，
        // 所以只把艺人页自己的头像替换成空内容。
        // 列表头像另走下方受尺寸约束的 after overlay；旧 replace 版本丢掉
        // 宿主 Modifier 后 fillMaxSize 曾撑爆网格，不能复用那种绘制方式。
        avatarHandles = runCatching {
            listOf(
                WorkshopApi.hookRegistrar.replaceComposable(
                    className = targets.artistAvatarClass,
                    methodName = HookTargets.ARTIST_AVATAR_METHOD,
                    inClass = targets.artistPageClass,
                    parameterTypes = HookTargets.ARTIST_AVATAR_PARAMS,
                    priority = 200,
                ) {
                    // 刻意什么都不画
                },
            )
        }.onFailure {
            log.log("avatar hook skipped: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull().orEmpty()
        log.log("avatar hook installed=${avatarHandles.size}")
        installDirectoryHooks(targets, screenDeps)
    }

    private fun installDirectoryHooks(targets: HookTargets.HostTargets, screenDeps: ArtistScreenDeps) {
        val directory = targets.directory ?: run {
            log.log("directory hooks skipped: host $hostVersion has no verified directory targets")
            return
        }
        val handles = mutableListOf<HookHandle>()
        runCatching {
            WorkshopApi.hookRegistrar.afterComposable(
                className = targets.artistAvatarClass,
                methodName = HookTargets.ARTIST_AVATAR_METHOD,
                inClass = directory.itemClass,
                parameterTypes = HookTargets.ARTIST_AVATAR_PARAMS,
                priority = 150,
            ) { call ->
                ArtistDirectoryAvatar(call.arguments.firstOrNull(), screenDeps)
            }
        }.onSuccess(handles::add).onFailure {
            log.log("directory avatar hook skipped: ${log.describe(it)}")
        }

        val bridge = ArtistDirectoryBridge(log)
        runCatching {
            WorkshopApi.hookRegistrar.appendContent(
                className = HookTargets.DIRECTORY_GRID_CLASS,
                methodName = HookTargets.DIRECTORY_GRID_METHOD,
                inClass = directory.screenClass,
                parameter = "content",
                scopeType = LazyGridScope::class.java,
                parameterTypes = HookTargets.DIRECTORY_GRID_PARAMS,
                priority = 100,
            ) { scope, _ ->
                bridge.reorder(scope, screenDeps.settings.favorites(), screenDeps.settings.read().favoritesFirst)
            }
        }.onSuccess(handles::add).onFailure {
            log.log("directory favorites hook skipped: ${log.describe(it)}")
        }
        directoryHandles = handles
        log.log("directory hooks installed=${directoryHandles.size}")
    }

    override fun stop() {
        ArtistMotionPlayers.closeAll()
        directoryHandles.forEach { handle ->
            runCatching { handle.unhook() }.onFailure { log.log("directory unhook failed: ${log.describe(it)}") }
        }
        directoryHandles = emptyList()
        avatarHandles.forEach { handle ->
            runCatching { handle.unhook() }.onFailure { log.log("avatar unhook failed: ${log.describe(it)}") }
        }
        avatarHandles = emptyList()
        hookHandle?.let { handle -> runCatching { handle.unhook() }.onFailure { log.log("unhook failed: ${log.describe(it)}") } }
        hookHandle = null
        deps?.settings?.close()
        deps?.artwork?.shutdown()
        deps = null
        log.log("stopped")
    }

    override fun update() {
        log.log("plugin update invoked")
    }

    private fun resolvePluginDir(): File? = runCatching {
        config?.getConfigPath()?.parent?.toFile()
            ?: File(pluginContext.pluginPath).let { if (it.isDirectory) it else it.parentFile }
    }.getOrNull()

    private fun logPath(): java.nio.file.Path? = runCatching {
        resolvePluginDir()?.toPath()?.resolve("diagnostics.log")
    }.getOrNull()

    private fun toast(text: String) {
        runCatching { WorkshopApi.ui.toast(text, WorkshopApi.Ui.ToastType.Warning) }
    }

    private fun resolveArtistName(route: Any?): String {
        if (route == null) return ""
        return HostReflection.stringProperty(route, "getName", "name").orEmpty()
    }

    /** 设置页按钮：打开写真目录，用户把高清写真按艺人名放进去即可。 */
    companion object {
        private const val CONFIG_FILE = "artist-page.json"

        @JvmStatic
        fun openPhotoDirectory() {
            runCatching {
                val config = WorkshopApi.manager.createConfigManager().getConfig(CONFIG_FILE)
                val dir = File(config.getConfigPath().parent.toFile(), "artist-photos")
                if (!dir.exists()) dir.mkdirs()
                java.awt.Desktop.getDesktop().open(dir)
            }
        }

        /** 设置页按钮：清空从 Apple Music 抓来的头图与艺术字缓存。 */
        @JvmStatic
        fun clearAppleMusicCache() {
            runCatching {
                val config = WorkshopApi.manager.createConfigManager().getConfig(CONFIG_FILE)
                val dir = config.getConfigPath().parent.toFile()
                var files = 0
                File(dir, "apple-music").let { images ->
                    images.listFiles()?.forEach { f -> if (f.isFile) { files++; f.delete() } }
                    images.delete()
                }
                File(dir, "apple-music-cache.tsv").let { tsv ->
                    if (tsv.isFile) { files++; tsv.delete() }
                }
                WorkshopApi.ui.toast(
                    "已清除 Apple Music 缓存（$files 个文件）",
                    WorkshopApi.Ui.ToastType.Success,
                )
            }
        }
    }
}

/**
 * Hook 内容的薄壳：把参数解析和异常兜底留在组合之外，页面本体只负责渲染。
 */
@Composable
private fun ArtistPageHost(artistName: String, deps: ArtistScreenDeps) {
    if (artistName.isBlank()) {
        Box(Modifier.fillMaxSize().background(Color(0xFF111318)), contentAlignment = Alignment.Center) {
            SaltText("Salt Artist：无法解析艺人名", color = Color.White.copy(alpha = 0.7f))
        }
        return
    }
    remember(artistName) { deps.log.log("render artist page: $artistName") }
    // 整块铺满内容槽，宿主自带的迷你播放条会画在覆盖层之上。
    Box(Modifier.fillMaxSize()) {
        ArtistScreen(artistName, deps)
    }
}
