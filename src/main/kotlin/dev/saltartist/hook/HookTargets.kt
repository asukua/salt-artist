package dev.saltartist.hook

/**
 * 逐版核对字节码后登记的 Hook 目标。升级宿主不能只扩大版本范围：
 * 1.19.1 的旧混淆类仍存在，但已不再承担 1.19.0 的艺人页职责。
 * 核查依据与复核入口见 docs/host-compatibility.md。
 *
 * 这里只存类名字符串；注册 Hook 之前不得用反射预加载转换目标。
 */
object HookTargets {
    data class HostTargets(
        val artistPageClass: String,
        val artistViewModelClass: String,
        val artistAvatarClass: String,
        val directory: DirectoryTargets? = null,
    ) {
        val artistPageParams: List<String> = listOf(ARTIST_ROUTE_PARAM, artistViewModelClass)
    }

    /** Optional directory hooks are verified independently from the artist detail page. */
    data class DirectoryTargets(
        val screenClass: String,
        val itemClass: String,
    )

    const val ARTIST_PAGE_METHOD = "\u037F"
    const val ARTIST_PAGE_CALLSITE =
        "com.xuncorp.voxzen.ui.navigation.ComposableSingletons\$AppNavigation_desktopKt"
    const val ARTIST_ROUTE_PARAM = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ArtistContent"
    const val ARTIST_AVATAR_METHOD = "\u037F"
    val ARTIST_AVATAR_PARAMS = listOf(
        "com.xuncorp.voxzen.data.entity.Artist",
        "androidx.compose.ui.Modifier",
    )
    const val DIRECTORY_GRID_CLASS = "androidx.compose.foundation.lazy.grid.LazyGridDslKt"
    const val DIRECTORY_GRID_METHOD = "LazyVerticalGrid"
    val DIRECTORY_GRID_PARAMS = listOf(
        "androidx.compose.foundation.lazy.grid.GridCells",
        "androidx.compose.ui.Modifier",
        "androidx.compose.foundation.lazy.grid.LazyGridState",
        "androidx.compose.foundation.layout.PaddingValues",
        "boolean",
        "androidx.compose.foundation.layout.Arrangement\$Vertical",
        "androidx.compose.foundation.layout.Arrangement\$Horizontal",
        "androidx.compose.foundation.gestures.FlingBehavior",
        "boolean",
        "androidx.compose.foundation.OverscrollEffect",
        "kotlin.jvm.functions.Function1",
    )

    // Unicode 转义固定码点，避免命令行编码或相似字形导致核对错误。
    private val verifiedHosts = mapOf(
        "1.19.0" to HostTargets(
            artistPageClass = "androidx.compose.ui.\u0137",
            artistViewModelClass = "androidx.compose.ui.\u0179",
            artistAvatarClass = "androidx.compose.ui.\u0219",
        ),
        "1.19.1" to HostTargets(
            artistPageClass = "androidx.compose.ui.\u03B8",
            artistViewModelClass = "androidx.compose.ui.\u10A9",
            artistAvatarClass = "androidx.compose.ui.\u0843",
            directory = DirectoryTargets(
                screenClass = "androidx.compose.ui.\u0417",
                itemClass = "androidx.compose.ui.\u10A5",
            ),
        ),
    )

    val supportedHostVersions: Set<String> get() = verifiedHosts.keys

    /** 保留宿主渠道/构建后缀的既有处理，未知版本保持原生页。 */
    fun forHost(version: String): HostTargets? = verifiedHosts[version.substringBefore('-')]

    fun isCompatibleHost(version: String): Boolean = forHost(version) != null
}
