package dev.saltartist.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import dev.saltartist.PluginLog

/**
 * 宿主导航栈：`AppNavigationKt.getLocalNavigator()` 暴露一个
 * `ProvidableCompositionLocal<Navigator>`。整页替换后原生返回按钮与专辑入口都不存在了，
 * 页面必须自己调用 Navigator.back() / navigate(AlbumContent)。
 */
class NavigationBridge(private val log: PluginLog) {
    private val localNavigator: CompositionLocal<Any>? by lazy {
        val method = runCatching {
            Class.forName(APP_NAVIGATION_CLASS).methods
                .firstOrNull { it.name == "getLocalNavigator" && it.parameterCount == 0 }
        }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val local = runCatching { method?.invoke(null) as? CompositionLocal<Any> }.getOrNull()
        log.logOnce("navigator-local", "getLocalNavigator available=${local != null}")
        local
    }

    /** 必须在 @Composable 作用域内调用；宿主缺失时返回 null。 */
    @Composable
    fun navigator(): Any? = localNavigator?.current

    fun back(navigator: Any?): Boolean {
        val method = HostReflection.noArgMethod(navigator, "back") ?: return false
        return runCatching { method.invoke(navigator); true }.getOrDefault(false)
    }

    /** 打开宿主原生专辑页；原生页保留专辑内歌曲与播放能力。 */
    fun openAlbum(navigator: Any?, albumTitle: String, artist: String): Boolean {
        if (navigator == null || albumTitle.isBlank()) return false
        val route = runCatching {
            val type = Class.forName(ALBUM_ROUTE_CLASS)
            type.getConstructor(String::class.java, String::class.java)
                .newInstance(albumTitle, artist)
        }.getOrNull() ?: return false
        val navigate = runCatching {
            navigator.javaClass.methods.firstOrNull {
                it.name == "navigate" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(route.javaClass)
            }
        }.getOrNull() ?: return false
        val ok = runCatching { navigate.invoke(navigator, route); true }.getOrDefault(false)
        log.log("navigator.navigate(AlbumContent(\"$albumTitle\")) ok=$ok")
        return ok
    }

    private companion object {
        const val APP_NAVIGATION_CLASS = "com.xuncorp.voxzen.ui.navigation.AppNavigationKt"
        const val ALBUM_ROUTE_CLASS = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$AlbumContent"
    }
}
