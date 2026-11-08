package dev.saltartist.bridge

import dev.saltartist.settings.DirectoryStyle
import androidx.compose.runtime.derivedStateOf
import com.xuncorp.voxzen.util.AppConfig
import com.xuncorp.voxzen.util.TestWindowStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectoryHostStyleTest {
    @Test fun recognizesVerifiedHostStyles() {
        assertEquals(DirectoryStyle.CLASSIC, directoryHostStyle("None"))
        assertEquals(DirectoryStyle.ACRYLIC, directoryHostStyle("WindowsAcrylic"))
        assertEquals(DirectoryStyle.MICA, directoryHostStyle("WindowsMica"))
        assertEquals(DirectoryStyle.MICA_ALT, directoryHostStyle("WindowsMicaAlt"))
        assertNull(directoryHostStyle("unknown"))
    }

    @Test fun followsChangesInTheHostsSnapshotState() {
        val bridge = ArtistDirectoryThemeBridge()
        val current = derivedStateOf { bridge.windowStyle() }
        try {
            for ((host, expected) in listOf(
                TestWindowStyle.None to DirectoryStyle.CLASSIC,
                TestWindowStyle.WindowsAcrylic to DirectoryStyle.ACRYLIC,
                TestWindowStyle.WindowsMica to DirectoryStyle.MICA,
                TestWindowStyle.WindowsMicaAlt to DirectoryStyle.MICA_ALT,
            )) {
                AppConfig.spwWindowStyle = host
                assertEquals(expected, current.value)
            }
        } finally { AppConfig.spwWindowStyle = TestWindowStyle.None }
    }
}
