package dev.saltartist.ui

import dev.saltartist.PluginLog
import dev.saltartist.artwork.ArtworkStore
import dev.saltartist.bridge.NavigationBridge
import dev.saltartist.bridge.PlaybackBridge
import dev.saltartist.data.ArtistRepository
import dev.saltartist.settings.ArtistSettings
import dev.saltartist.settings.ArtistProfileStore

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
)
