package dev.saltartist.settings

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.nio.file.Path
import kotlin.test.*

@OptIn(UnstableSpwWorkshopApi::class)
class DirectoryBackgroundTest {
    @Test
    fun hostMaterialFollowsRecognizedWindowStylesOnly() {
        for (style in DirectoryStyle.entries) {
            assertTrue(directoryUsesHostBackdrop(DirectoryBackgroundMode.SYSTEM, style))
            assertFalse(directoryUsesHostBackdrop(DirectoryBackgroundMode.CUSTOM, style))
        }
        assertFalse(directoryUsesHostBackdrop(DirectoryBackgroundMode.SYSTEM, null))
    }

    @Test
    fun followHostPersistsWithoutChangingCustomSettings() {
        val config = BackgroundConfig(true)
        val material = DirectoryBackground(DirectoryBackgroundMode.SYSTEM, "#987654", "wallpaper.png")
        ArtistSettings(config).use { assertTrue(it.setDirectoryBackground(material)) }
        ArtistSettings(config).use { assertEquals(material, it.read().directoryBackground) }
    }

    @Test
    fun oldAndInvalidPreferencesFollowHostAndKeepASafeCustomColor() {
        for (values in listOf(emptyMap(), mapOf(ArtistSettings.KEY_DIRECTORY_BACKGROUND to "bad", ArtistSettings.KEY_DIRECTORY_COLOR to "#oops"))) {
            val background = artistSettingsFromValues(values).directoryBackground
            assertEquals(DirectoryBackgroundMode.SYSTEM, background.mode)
            assertEquals("#243448", background.color)
        }
        assertNull(parseBackgroundColor("#fff"))
        assertNull(parseBackgroundColor("#00ffffff"))
        assertNotNull(parseBackgroundColor("aBcD12"))
    }

    @Test
    fun customAppearancePersistsAcrossReloadAndDoesNotChangeArtistDetailSettings() {
        val config = BackgroundConfig(true)
        val custom = DirectoryBackground(DirectoryBackgroundMode.CUSTOM, "#987654", "C:\\Pictures\\艺人.png")
        ArtistSettings(config).use { settings ->
            assertTrue(settings.setDirectoryBackground(custom))
            assertEquals(custom, settings.read().directoryBackground)
            assertTrue(settings.read().immersiveHero)
        }
        ArtistSettings(config).use { assertEquals(custom, it.read().directoryBackground) }
    }

    @Test
    fun legacyBackgroundChoicesArePreservedOrMigrated() {
        for (old in listOf("LIGHT", "DARK", "MATERIAL")) {
            val config = BackgroundConfig(true)
            config.values[ArtistSettings.KEY_DIRECTORY_BACKGROUND] = old
            ArtistSettings(config).use { assertEquals(DirectoryBackgroundMode.SYSTEM, it.read().directoryBackground.mode) }
            assertEquals("SYSTEM", config.values[ArtistSettings.KEY_DIRECTORY_BACKGROUND])
        }
        val custom = BackgroundConfig(true)
        custom.values[ArtistSettings.KEY_DIRECTORY_BACKGROUND] = "CUSTOM"
        ArtistSettings(custom).use { assertEquals(DirectoryBackgroundMode.CUSTOM, it.read().directoryBackground.mode) }
        assertEquals("CUSTOM", custom.values[ArtistSettings.KEY_DIRECTORY_BACKGROUND])
        val failed = BackgroundConfig(false)
        failed.values[ArtistSettings.KEY_DIRECTORY_BACKGROUND] = "MATERIAL"
        ArtistSettings(failed).use { assertEquals(DirectoryBackgroundMode.SYSTEM, it.read().directoryBackground.mode) }
        assertEquals("MATERIAL", failed.values[ArtistSettings.KEY_DIRECTORY_BACKGROUND])
    }

    @Test
    fun failedSaveRollsBackEveryBackgroundField() {
        val config = BackgroundConfig(false)
        ArtistSettings(config).use { settings ->
            assertFalse(settings.setDirectoryBackground(DirectoryBackground(DirectoryBackgroundMode.CUSTOM, "#ffffff", "image.png")))
            assertEquals(DirectoryBackground(), settings.read().directoryBackground)
            assertEquals("SYSTEM", config.values[ArtistSettings.KEY_DIRECTORY_BACKGROUND])
            assertEquals("#243448", config.values[ArtistSettings.KEY_DIRECTORY_COLOR])
            assertEquals("", config.values[ArtistSettings.KEY_DIRECTORY_IMAGE])
        }
    }

    private class BackgroundConfig(val saveResult: Boolean) : ConfigHelper {
        val values = mutableMapOf<String, Any>()
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: String, defaultValue: T): T = (values[key] ?: defaultValue) as T
        override fun set(key: String, value: Any) { values[key] = value }
        override fun save(): Boolean = saveResult
        override fun reload(): Boolean = true
        override fun getConfigPath(): Path = Path.of("build", "background-test-config-not-written.json")
    }
}
