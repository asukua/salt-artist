package dev.saltartist.bridge

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.saltartist.PluginLog
import dev.saltartist.data.ArtistTrack
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier

/**
 * SPW 1.19.3 main-song TrackLazyColumn, including its native selection,
 * playback and popup handling. Resolve only after page hooks exist.
 * Other host profiles keep the plugin list until separately verified.
 */
internal class NativeTrackListBridge(private val log: PluginLog, private val verifiedHost: Boolean) {
    private data class LocalContract(val local: CompositionLocal<Any>, val valueType: Class<*>)
    private data class Access(
        val render: Method,
        val trackType: Class<*>,
        val group: Constructor<*>,
        val noGroup: Any,
        val display: List<Method>,
        val locals: List<LocalContract>,
    )

    private val access: Access? by lazy {
        if (!verifiedHost) null else runCatching { inspect() }.getOrElse {
            log.logOnce("native-track-list-contract", "native song list unavailable: ${log.describe(it)}")
            null
        }
    }

    /** No host composable is entered while validating data or the live theme. */
    @OptIn(InternalComposeApi::class)
    @Composable
    fun hasHostContext(): Boolean {
        val current = access ?: return false
        val composer = currentComposer
        return current.locals.all { contract ->
            // Reading an absent Local can throw its default-factory error. It
            // does not open any native composition group, so fallback is safe.
            runCatching { contract.valueType.isInstance(composer.consume(contract.local)) }.getOrDefault(false)
        }
    }

    fun prepare(tracks: List<ArtistTrack>, originals: Map<String, Any>): Prepared? {
        val current = access ?: return null
        return runCatching {
            val selected = matchingNativeTracks(tracks, originals, current.trackType) { track, native ->
                val values = current.display.map { it.invoke(native) }
                values == listOf(track.id, track.title, track.artist, track.album, track.albumArtist, track.durationMs)
            } ?: return null
            Prepared(current.render, listOf(current.group.newInstance(current.noGroup, selected)))
        }.getOrElse {
            log.logOnce("native-track-list-data", "native song list kept plugin display: ${log.describe(it)}")
            null
        }
    }

    internal class Prepared internal constructor(private val render: Method, private val groups: List<Any>) {
        @OptIn(InternalComposeApi::class)
        @Composable
        fun Content(modifier: Modifier = Modifier) {
            // Never catch an exception and compose a fallback in this Composer:
            // a native renderer may already have opened groups. All optional
            // compatibility decisions happen before this invocation.
            render.invoke(null, groups, modifier, null, false, false, false, null, null, null, null,
                PaddingValues(bottom = 112.dp), currentComposer, 0, 0, DEFAULTS)
        }
    }

    private fun inspect(): Access {
        fun type(name: String) = checkNotNull(HostReflection.classOrNull(name)) { "missing $name" }
        fun ui(suffix: String) = type("androidx.compose.ui.$suffix")
        val listType = ui("\u05EA")
        val groupType = ui("\u06CB")
        val stateType = ui("\u049B")
        val noGroupType = ui("\u03D3\$\u0528")
        val trackType = type("com.xuncorp.voxzen.data.entity.Track")
        val render = listType.getDeclaredMethod("\u037F", List::class.java, Modifier::class.java,
            stateType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, ui("\u0936"), Function2::class.java, ui("\u071A"),
            ui("\u066F"), PaddingValues::class.java, Composer::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        require(JavaModifier.isPublic(render.modifiers) && JavaModifier.isStatic(render.modifiers) &&
            render.returnType == Void.TYPE)
        val group = groupType.getConstructor(Any::class.java, List::class.java)
        val singleton = noGroupType.getField("\u037F")
        require(JavaModifier.isStatic(singleton.modifiers) && singleton.type == noGroupType)
        val noGroup = checkNotNull(singleton.get(null))
        val display = listOf("getId", "getTitle", "getArtist", "getAlbum", "getAlbumArtist", "getDuration")
            .mapIndexed { index, name ->
                trackType.getMethod(name).also {
                    require(!JavaModifier.isStatic(it.modifiers) && it.returnType ==
                        if (index == 5) Long::class.javaPrimitiveType else String::class.java)
                }
            }
        val themeLocals = ui("\u02B6")
        val expectedTypes = listOf("\u02E4", "\u02B4", "\u02B2", "\u02E1", "\u02EE", "\u07F4", "\u093D")
        val getters = listOf("\u037F", "\u0528", "\u0529", "\u052A", "\u052B", "\u052C", "\u052D")
        fun local(owner: Class<*>, method: String, expected: Class<*>): LocalContract {
            val getter = owner.getMethod(method)
            require(JavaModifier.isStatic(getter.modifiers) &&
                CompositionLocal::class.java.isAssignableFrom(getter.returnType))
            @Suppress("UNCHECKED_CAST")
            return LocalContract(getter.invoke(null) as CompositionLocal<Any>, expected)
        }
        val ownerField = ui("\u052F").getDeclaredField("\u0529").also {
            require(JavaModifier.isStatic(it.modifiers) && CompositionLocal::class.java.isAssignableFrom(it.type))
            it.isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val ownerLocal = ownerField.get(null) as CompositionLocal<Any>
        val locals = getters.zip(expectedTypes).map { (getter, expected) -> local(themeLocals, getter, ui(expected)) } +
            listOf(
                local(type("com.xuncorp.voxzen.ui.navigation.AppNavigationKt"), "getLocalNavigator",
                    type("com.xuncorp.voxzen.ui.navigation.Navigator")),
                local(type("androidx.compose.ui.platform.CompositionLocalsKt"), "getLocalClipboard",
                    type("androidx.compose.ui.platform.Clipboard")),
                local(ui("\u078C"), "\u037F", type("androidx.savedstate.SavedStateRegistryOwner")),
                LocalContract(ownerLocal, type("androidx.lifecycle.ViewModelStoreOwner")),
            )
        return Access(render, trackType, group, noGroup, display, locals)
    }

    private companion object {
        // Parameters 2..9 use native defaults; list, modifier and padding remain
        // explicit. Native defaults create remembered state and honor AppConfig.
        const val DEFAULTS = 1020
    }
}

/** Complete, ordered originals only; never replace edited display metadata. */
internal fun matchingNativeTracks(
    tracks: List<ArtistTrack>,
    originals: Map<String, Any>,
    type: Class<*>,
    matchesDisplay: (ArtistTrack, Any) -> Boolean,
): List<Any>? {
    if (tracks.isEmpty() || tracks.any { it.id.isBlank() } || tracks.map { it.id }.toSet().size != tracks.size) return null
    val result = ArrayList<Any>(tracks.size)
    for (track in tracks) {
        val original = originals[track.id] ?: return null
        if (!type.isInstance(original) || !matchesDisplay(track, original)) return null
        result += original
    }
    return result
}
