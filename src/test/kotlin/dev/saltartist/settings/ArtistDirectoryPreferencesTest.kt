package dev.saltartist.settings

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.nio.file.Path
import kotlin.test.*

@OptIn(UnstableSpwWorkshopApi::class)
class ArtistDirectoryPreferencesTest {
    @Test fun removeAndRestoreRoundTripWithoutLosingUnicodeOrDelimiters() {
        val names = setOf("初音ミク", "AC/DC", "AC DC", "A\nB", "名字=;test")
        assertEquals(names, decodeHiddenArtists(encodeHiddenArtists(names)))
        assertTrue(decodeHiddenArtists("???").isEmpty())
        assertFalse(ArtistDirectoryPreferences(hidden = setOf("AC/DC")).isHidden("AC DC"))
    }

    @Test fun savesViewSizeSortAndHiddenNamesAndReloadsThem() {
        val config = MemoryConfig()
        ArtistSettings(config).use { settings ->
            assertTrue(settings.updateDirectory { it.copy(view = ArtistDirectoryView.COMPACT, size = ArtistDirectorySize.SMALL,
                sort = ArtistDirectorySort.ADDED, hidden = setOf("艺人")) })
        }
        ArtistSettings(config).use { settings ->
            assertEquals(ArtistDirectoryPreferences(ArtistDirectoryView.COMPACT, ArtistDirectorySize.SMALL,
                ArtistDirectorySort.ADDED, setOf("艺人")), settings.read().directory)
            assertTrue(settings.updateDirectory { it.copy(hidden = emptySet()) })
            assertFalse(settings.read().directory.isHidden("艺人"))
        }
    }

    @Test fun failedSaveLeavesActiveAndConfigValuesUnchanged() {
        val config = MemoryConfig(false)
        ArtistSettings(config).use { settings ->
            assertFalse(settings.updateDirectory { it.copy(hidden = setOf("Artist"), view = ArtistDirectoryView.LIST) })
            assertEquals(ArtistDirectoryPreferences(), settings.read().directory)
            assertEquals("GRID", config.values[ArtistSettings.KEY_DIRECTORY_VIEW])
            assertEquals("", config.values[ArtistSettings.KEY_HIDDEN_ARTISTS])
        }
    }

    @Test fun corruptDirectoryAndFrameRateOptionsUseDefaults() {
        val result = artistSettingsFromValues(mapOf(ArtistSettings.KEY_DIRECTORY_VIEW to "broken",
            ArtistSettings.KEY_MOTION_FPS to "999", ArtistSettings.KEY_SCROLL_BLUR to false))
        assertEquals(ArtistDirectoryPreferences(), result.directory)
        assertEquals(30, result.motionFrameRate)
        assertFalse(result.scrollHeroBlur)
        assertEquals(24, artistSettingsFromValues(mapOf(ArtistSettings.KEY_MOTION_FPS to "24")).motionFrameRate)
    }

    private class MemoryConfig(val succeeds: Boolean = true) : ConfigHelper {
        val values = mutableMapOf<String, Any>()
        @Suppress("UNCHECKED_CAST") override fun <T> get(key: String, defaultValue: T): T = (values[key] ?: defaultValue) as T
        override fun set(key: String, value: Any) { values[key] = value }
        override fun save() = succeeds
        override fun reload() = true
        override fun getConfigPath(): Path = Path.of("build", "directory-test-not-written.json")
    }
}
