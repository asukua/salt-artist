package dev.saltartist.ui

import androidx.compose.foundation.Indication
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import dev.saltartist.bridge.ArtistDirectoryThemeBridge
import dev.saltartist.bridge.NavigationBridge
import dev.saltartist.PluginLog
import dev.saltartist.hook.HookTargets
import dev.saltartist.settings.DirectoryBackgroundMode
import dev.saltartist.settings.DirectoryStyle
import dev.saltartist.settings.directoryUsesHostBackdrop
import java.lang.reflect.Proxy
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class HostNavigationTintRuntimeTest {
    @OptIn(InternalComposeApi::class)
    @Composable
    private fun hostNavigationBackground() {
        val method = remember {
            Class.forName("androidx.compose.ui.\u06BA").getMethod("\u037F", Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Modifier::class.java,
                Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }
        method.invoke(null, 0f, 0f, true, Modifier, currentComposer, 0, 0)
    }

    @Test fun realHostNavigationTintRestoresBothThemes() {
        val configType = Class.forName("androidx.compose.ui.\u02E4")
        val localType = Class.forName("androidx.compose.ui.\u02B6")
        val expectedArchive = File(System.getProperty("host.archive")).canonicalFile
        assertEquals(expectedArchive, File(Class.forName("androidx.compose.ui.\u06BA").protectionDomain.codeSource.location.toURI()).canonicalFile)
        assertEquals(expectedArchive, File(configType.protectionDomain.codeSource.location.toURI()).canonicalFile)
        @Suppress("UNCHECKED_CAST")
        val local = localType.getMethod("\u037F").invoke(null) as ProvidableCompositionLocal<Any>
        val indication = Proxy.newProxyInstance(Indication::class.java.classLoader, arrayOf(Indication::class.java)) { _, _, _ -> null }
        for ((dark, expected) in listOf(false to Color(0x64FFFFFF), true to Color(0x1E3A3A3A))) for (preserve in listOf(false, true)) for (style in DirectoryStyle.entries) {
            val config = configType.getConstructor(Boolean::class.javaPrimitiveType, Indication::class.java).newInstance(dark, indication)
            val scene = ImageComposeScene(120, 100) {
                CompositionLocalProvider(local provides config) {
                    val tint = remember { ArtistDirectoryThemeBridge() }.navigationTint(HookTargets.forHost("1.19.3")!!.directory)
                    val base = if (style == DirectoryStyle.CLASSIC) {
                        if (dark) Color(0xFF202020) else Color(0xFFF3F3F3)
                    } else Color(0xFF8FB1DA)
                    Box(Modifier.fillMaxSize().background(base)) {
                        Box(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                            if (preserve) hostNavigationBackground()
                            else Box(Modifier.fillMaxSize().background(expected))
                            Box(Modifier.fillMaxSize().padding(top = 40.dp)) {
                                Box(Modifier.fillMaxSize().background(Color.Red))
                                Box(Modifier.fillMaxSize().directoryBackdrop(
                                    directoryUsesHostBackdrop(DirectoryBackgroundMode.SYSTEM, style), Color.Black, tint ?: Color.Transparent))
                            }
                        }
                    }
                }
            }
            try {
                scene.render(System.nanoTime()).use { image ->
                    val bitmap = image.encodeToData(EncodedImageFormat.PNG)!!.use { ImageIO.read(it.bytes.inputStream()) }
                    assertEquals(bitmap.getRGB(80, 20), bitmap.getRGB(80, 80), "Real host tint mismatch, dark=$dark preserve=$preserve style=$style")
                }
            } finally { scene.close() }
        }
    }

    @Test fun realNavigatorBackStackIsObserved() {
        val artist = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Artist").getField("INSTANCE").get(null)
        val track = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Track").getField("INSTANCE").get(null)
        val stackType = Class.forName("androidx.navigation3.runtime.NavBackStack")
        @Suppress("UNCHECKED_CAST")
        val stack = stackType.getConstructor().newInstance() as MutableList<Any>
        stack.add(artist)
        val sheetValueType = Class.forName("androidx.compose.ui.\u1077")
        val inner = Class.forName("androidx.compose.ui.\u1076").getConstructor(sheetValueType, Density::class.java,
            AnimationSpec::class.java, Function1::class.java).newInstance(sheetValueType.enumConstants.first(),
            Density(1f), spring<Float>(), { _: Any -> true })
        val sheetType = Class.forName("com.xuncorp.voxzen.ui.component.PlayerSheetState")
        val sheet = sheetType.constructors.single().newInstance(inner)
        val navigatorType = Class.forName("com.xuncorp.voxzen.ui.navigation.Navigator")
        val navigator = navigatorType.constructors.single().newInstance(artist, setOf(artist, track), stack, sheet)
        val bridge = NavigationBridge(PluginLog(null))
        val active = derivedStateOf { bridge.isArtistDirectoryCurrent(navigator) }
        assertTrue(active.value)
        stack.add(track)
        assertFalse(active.value)
        stack.removeAt(stack.lastIndex)
        assertTrue(active.value)
    }

    @Test fun realHostTransitionHasNoBackgroundBoxOnExit() {
        val configType = Class.forName("androidx.compose.ui.\u02E4")
        @Suppress("UNCHECKED_CAST")
        val themeLocal = Class.forName("androidx.compose.ui.\u02B6").getMethod("\u037F").invoke(null) as ProvidableCompositionLocal<Any>
        @Suppress("UNCHECKED_CAST")
        val transitionLocal = Class.forName("androidx.navigation3.ui.LocalNavAnimatedContentScopeKt")
            .getMethod("getLocalNavAnimatedContentScope").invoke(null) as ProvidableCompositionLocal<Any>
        val expectedArchive = File(System.getProperty("host.archive")).canonicalFile
        assertEquals(expectedArchive, File(Class.forName("androidx.navigation3.ui.LocalNavAnimatedContentScopeKt").protectionDomain.codeSource.location.toURI()).canonicalFile)
        val indication = Proxy.newProxyInstance(Indication::class.java.classLoader, arrayOf(Indication::class.java)) { _, _, _ -> null }
        val config = configType.getConstructor(Boolean::class.javaPrimitiveType, Indication::class.java).newInstance(true, indication)

        fun frames(pop: Boolean, fixed: Boolean, stepNanos: Long): List<Pair<Int, Int>> {
            val active = mutableStateOf(true)
            val scene = ImageComposeScene(120, 100) {
                CompositionLocalProvider(themeLocal provides config) {
                    val theme = remember { ArtistDirectoryThemeBridge() }
                    val navigation = remember { NavigationBridge(PluginLog(null)) }
                    val hostTint = theme.navigationTint(HookTargets.forHost("1.19.3")!!.directory) ?: Color.Transparent
                    Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                        Box(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                            hostNavigationBackground()
                            AnimatedContent(active.value, modifier = Modifier.fillMaxSize().padding(top = 40.dp), transitionSpec = {
                                if (pop) fadeIn(spring(stiffness = 500f)) togetherWith
                                    (slideOutVertically(spring(stiffness = 500f)) { 150 } + fadeOut(spring(stiffness = 500f)))
                                else slideInVertically(spring(stiffness = 500f)) { 150 } togetherWith fadeOut(spring(stiffness = 500f))
                            }, label = "host-directory-navigation") { directory ->
                                val animatedScope = this
                                CompositionLocalProvider(transitionLocal provides animatedScope) {
                                    if (directory) {
                                        val fading = if (!active.value && fixed) navigation.isDirectoryExitFading() else false
                                        Box(Modifier.fillMaxSize()) {
                                            Box(Modifier.fillMaxSize().background(Color.Red))
                                            Box(Modifier.fillMaxSize().directoryBackdrop(true, Color.Black,
                                                directoryExitTint(active.value, fading, hostTint)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            var time = 1_000_000_000L
            fun frame(): Pair<Int, Int> {
                Snapshot.sendApplyNotifications()
                time += stepNanos
                scene.render(time).use { image ->
                    return image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        val bitmap = ImageIO.read(it.bytes.inputStream())
                        bitmap.getRGB(80, 20) to bitmap.getRGB(80, 80)
                    }
                }
            }
            return try {
                repeat(4) { frame() }
                active.value = false
                List(50) { frame() }
            } finally { scene.close() }
        }
        for (pop in listOf(false, true)) for (step in listOf(8_333_333L, 16_666_667L, 33_333_333L)) {
            assertTrue(frames(pop, false, step).any { it.first != it.second }, "Original box not reproduced")
            val actual = frames(pop, true, step)
            assertTrue(actual.all { it.first == it.second }, "Background changed: pop=$pop step=$step frames=$actual")
        }
    }
}
