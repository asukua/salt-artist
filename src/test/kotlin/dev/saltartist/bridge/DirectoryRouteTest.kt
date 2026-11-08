package dev.saltartist.bridge

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import com.xuncorp.voxzen.ui.navigation.Navigator
import com.xuncorp.voxzen.ui.navigation.ScreenRoute
import dev.saltartist.PluginLog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectoryRouteTest {
    @Test fun outgoingDirectoryTracksTheSnapshotBackStack() {
        val stack = mutableStateListOf<Any>(ScreenRoute.Artist)
        val bridge = NavigationBridge(PluginLog(null))
        val navigator = Navigator(stack)
        val current = derivedStateOf { bridge.isArtistDirectoryCurrent(navigator) }
        assertTrue(current.value)
        stack.add(ScreenRoute.Track)
        assertFalse(current.value)
        stack.removeAt(stack.lastIndex)
        assertTrue(current.value)
        stack.clear()
        assertTrue(current.value)
        assertTrue(bridge.isArtistDirectoryCurrent(null))
    }
}
