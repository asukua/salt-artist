package dev.saltartist.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import dev.saltartist.PluginLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier

/**
 * SPW 1.19.3's TrackContextMenu, used by preview rows and the plugin fallback
 * list. The native TrackLazyColumn already owns its own context menu.
 *
 * Keep this disabled for other host profiles: a matching obfuscated name alone
 * does not establish that the implementation still has the same responsibility.
 */
internal class NativeTrackMenuBridge(private val log: PluginLog, private val enabled: Boolean) {
    private data class LocalContract(val local: CompositionLocal<Any>, val valueType: Class<*>)
    private data class Access(
        val render: Method,
        val trackType: Class<*>,
        val state: Constructor<*>,
        val open: Method,
        val close: Method,
        val locals: List<LocalContract>,
    )

    private val access: Access? by lazy {
        if (!enabled) null else runCatching { inspect() }.getOrElse {
            log.logOnce("native-track-menu-contract", "native song menu unavailable: ${log.describe(it)}")
            null
        }
    }

    /** Supply the original host Track; Workshop MediaItem does not carry its data. */
    fun prepare(rawTrack: Any): Handle? {
        val current = access ?: return null
        if (!current.trackType.isInstance(rawTrack)) return null
        return runCatching {
            Handle(current.render, current.state.newInstance(false), current.open, current.close,
                setOf(rawTrack), { composer -> hasContext(current, composer) }, log)
        }.getOrElse {
            log.logOnce("native-track-menu-state", "native song menu state unavailable: ${log.describe(it)}")
            null
        }
    }

    @OptIn(InternalComposeApi::class)
    private fun hasContext(current: Access, composer: Composer): Boolean = current.locals.all { contract ->
        // consume() can fail in an isolated test or outside the host theme. No
        // native composable has opened groups yet, so this fallback is safe.
        runCatching { contract.valueType.isInstance(composer.consume(contract.local)) }.getOrDefault(false)
    }

    internal class Handle internal constructor(
        private val render: Method,
        private val state: Any,
        private val open: Method,
        private val close: Method,
        private val tracks: Set<Any>,
        private val hasContext: (Composer) -> Boolean,
        private val log: PluginLog,
    ) {
        @Volatile
        private var contextReady = false

        /** Consume a secondary press only when the native menu can be opened. */
        fun open(): Boolean {
            if (!contextReady) return false
            return runCatching { open.invoke(state); true }.getOrElse {
                log.logOnce("native-track-menu-open", "native song menu did not open: ${log.describe(it)}")
                false
            }
        }

        @OptIn(InternalComposeApi::class)
        @Composable
        fun Content() {
            val composer = currentComposer
            val ready = hasContext(composer)
            DisposableEffect(this, ready) {
                contextReady = ready
                onDispose {
                    contextReady = false
                    runCatching { close.invoke(state) }
                }
            }
            if (!ready) return

            // Native menu supplies current-cursor placement, actions, dismissal,
            // nested menus and dialogs. Do not wrap it in a second animation.
            // Never catch an invocation failure and compose a fallback here:
            // the native implementation may already have opened Composer groups.
            render.invoke(null, state, tracks, null, composer, 0, DEFAULT_OPTIONS)
        }
    }

    private fun inspect(): Access {
        fun type(name: String) = checkNotNull(HostReflection.classOrNull(name)) { "missing $name" }
        fun ui(suffix: String) = type("androidx.compose.ui.$suffix")
        val trackType = type("com.xuncorp.voxzen.data.entity.Track")
        val stateType = ui("\u05DC")
        val render = ui("\u0E09").getDeclaredMethod("\u037F", stateType, Set::class.java,
            ui("\u066F"), Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        require(JavaModifier.isPublic(render.modifiers) && JavaModifier.isStatic(render.modifiers) &&
            render.returnType == Void.TYPE)
        val state = stateType.getConstructor(Boolean::class.javaPrimitiveType)
        fun stateAction(name: String) = stateType.getMethod(name).also {
            require(JavaModifier.isPublic(it.modifiers) && !JavaModifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE)
        }

        fun local(owner: Class<*>, name: String, expected: Class<*>): LocalContract {
            val getter = owner.getMethod(name)
            require(JavaModifier.isStatic(getter.modifiers) &&
                CompositionLocal::class.java.isAssignableFrom(getter.returnType))
            @Suppress("UNCHECKED_CAST")
            return LocalContract(getter.invoke(null) as CompositionLocal<Any>, expected)
        }

        // All SaltTheme values used by native submenu/dialog content must exist
        // before entering TrackContextMenu. These are the 1.19.3 theme locals.
        val themeLocals = ui("\u02B6")
        val expectedTypes = listOf("\u02E4", "\u02B4", "\u02B2", "\u02E1", "\u02EE", "\u07F4", "\u093D")
        val getters = listOf("\u037F", "\u0528", "\u0529", "\u052A", "\u052B", "\u052C", "\u052D")
        val locals = getters.zip(expectedTypes).map { (getter, expected) ->
            local(themeLocals, getter, ui(expected))
        }.toMutableList()
        locals += local(type("com.xuncorp.voxzen.ui.navigation.AppNavigationKt"), "getLocalNavigator",
            type("com.xuncorp.voxzen.ui.navigation.Navigator"))
        locals += local(type("androidx.compose.ui.platform.CompositionLocalsKt"), "getLocalClipboard",
            type("androidx.compose.ui.platform.Clipboard"))
        locals += local(ui("\u078C"), "\u037F", type("androidx.savedstate.SavedStateRegistryOwner"))

        // This host exposes only a composable get-current accessor for the VM
        // owner. Reading its verified Local field avoids entering that accessor.
        val ownerLocal = ui("\u052F").getDeclaredField("\u0529")
        require(JavaModifier.isStatic(ownerLocal.modifiers) &&
            CompositionLocal::class.java.isAssignableFrom(ownerLocal.type))
        ownerLocal.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        locals += LocalContract(ownerLocal.get(null) as CompositionLocal<Any>,
            type("androidx.lifecycle.ViewModelStoreOwner"))
        return Access(render, trackType, state, stateAction("\u0528"), stateAction("\u0529"), locals)
    }

    private companion object {
        // Third business parameter is native TrackContextMenuOptions.
        const val DEFAULT_OPTIONS = 4
    }
}
