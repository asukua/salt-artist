package dev.saltartist.bridge

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.graphics.Color
import dev.saltartist.hook.HookTargets
import dev.saltartist.settings.DirectoryStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Modifier

/** SaltTheme ABI shared by verified directory hosts; never mutate its global config. */
internal class ArtistDirectoryThemeBridge {
    private var tintContract: String? = null
    private var tintMethod: java.lang.reflect.Method? = null

    @OptIn(InternalComposeApi::class)
    @Composable
    fun navigationTint(targets: HookTargets.DirectoryTargets?): Color? {
        val tintClass = targets?.navigationTintClass ?: return null
        if (tintClass != tintContract) {
            tintContract = tintClass
            tintMethod = runCatching {
                Class.forName(tintClass).getMethod("\u037F", Composer::class.java).also {
                    require(Modifier.isStatic(it.modifiers) && it.returnType == Long::class.javaPrimitiveType)
                }
            }.getOrNull()
        }
        val tint = tintMethod ?: return null
        val composer = currentComposer
        return runCatching { Color((tint.invoke(null, composer) as Long).toULong()) }.getOrNull()
    }

    private val windowStyle by lazy {
        runCatching {
            val type = Class.forName("com.xuncorp.voxzen.util.AppConfig")
            type.getField("INSTANCE").get(null) to type.getMethod("getSpwWindowStyle")
        }.getOrNull()
    }

    fun windowStyle(): DirectoryStyle? = runCatching {
        val (config, getter) = windowStyle ?: return null
        directoryHostStyle((getter.invoke(config) as Enum<*>).name)
    }.getOrNull()
    val configLocal: ProvidableCompositionLocal<Any> by lazy {
        @Suppress("UNCHECKED_CAST")
        (Class.forName("androidx.compose.ui.\u02B6").getMethod("\u037F").invoke(null) as ProvidableCompositionLocal<Any>)
    }
    val colorsLocal: ProvidableCompositionLocal<Any> by lazy {
        @Suppress("UNCHECKED_CAST")
        (Class.forName("androidx.compose.ui.\u02B6").getMethod("\u0528").invoke(null) as ProvidableCompositionLocal<Any>)
    }

    fun isDark(current: Any): Boolean {
        require(current.javaClass.name == "androidx.compose.ui.\u02E4")
        return current.javaClass.getMethod("\u037F").invoke(current) as Boolean
    }

    fun background(colors: Any, dark: Boolean): Color {
        require(colors.javaClass.name == "androidx.compose.ui.\u02B4")
        val palette = colors.javaClass.getMethod(if (dark) "\u0528" else "\u037F").invoke(colors)
        require(palette.javaClass.name == "androidx.compose.ui.\u0374")
        return Color((palette.javaClass.getMethod("\u052A").invoke(palette) as Long).toULong())
    }

    fun configFor(current: Any, dark: Boolean): Any {
        require(current.javaClass.name == "androidx.compose.ui.\u02E4")
        val copy = current.javaClass.getMethod("\u037F", current.javaClass, Boolean::class.javaPrimitiveType)
        require(Modifier.isStatic(copy.modifiers) && copy.returnType == current.javaClass)
        return copy.invoke(null, current, dark)
    }
}

internal fun directoryHostStyle(name: String): DirectoryStyle? = when (name) {
    "None" -> DirectoryStyle.CLASSIC
    "WindowsAcrylic" -> DirectoryStyle.ACRYLIC
    "WindowsMica" -> DirectoryStyle.MICA
    "WindowsMicaAlt" -> DirectoryStyle.MICA_ALT
    else -> null
}

/** The same unfiltered Artist flow consumed by the native directory ViewModel. */
internal suspend fun nativeLibraryArtistNames(targets: HookTargets.DirectoryTargets): List<String> = withTimeout(8_000L) {
    val type = Class.forName(targets.artistRepositoryClass)
    val getter = type.getMethod("\u0528")
    val repository = if (Modifier.isStatic(getter.modifiers)) null else {
        val companion = type.fields.single { Modifier.isStatic(it.modifiers) && it.type.name == "${type.name}\$\u037F" }.get(null)
        companion.javaClass.getMethod("\u037F").invoke(companion)
    }
    val flow = getter.invoke(repository) as? Flow<*> ?: error("Artist flow unavailable")
    val artists = flow.first() as? List<*> ?: error("Artist flow returned no list")
    artists.map { artist ->
        require(artist != null && artist.javaClass.name == "com.xuncorp.voxzen.data.entity.Artist")
        artist.javaClass.getMethod("getName").invoke(artist) as String
    }.filter(String::isNotBlank)
}
