package dev.saltartist.bridge

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.graphics.Color
import dev.saltartist.hook.HookTargets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Modifier

/** SaltTheme ABI shared by verified directory hosts; never mutate its global config. */
internal class ArtistDirectoryThemeBridge {
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
