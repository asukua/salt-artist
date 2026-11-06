package dev.saltartist.settings

import dev.saltartist.am.AmArtistDetails
import dev.saltartist.ui.showLibraryArtistInfo
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistSettingsTest {
    @Test
    fun missingPreferencesKeepChineseFirstAndUseOneRegionOrder() {
        val settings = artistSettingsFromValues(emptyMap())
        assertTrue(settings.preferChineseBiography)
        assertTrue(settings.languagePolicy.preferChineseBiography)
        assertEquals("US,JP,CN", settings.languagePolicy.storefronts)
        assertTrue(settings.favoritesFirst)
        assertTrue(settings.recognizeAliases)
        assertEquals("", settings.artistIdentityOverrides)
        assertEquals(listOf("US", "JP", "CN"), settings.identityPolicy.storefrontOrder)
        assertTrue(settings.animatedHero)
        assertEquals(listOf("US", "JP", "CN"), settings.visualPolicy.countries)
        assertTrue(settings.visualPolicy.automaticLanguage)
    }

    @Test
    fun oldLanguageAndCustomHeroRegionMigrateWithoutResettingOtherFeatures() {
        val settings = artistSettingsFromValues(mapOf(
            ArtistSettings.KEY_LANGUAGE to "ENGLISH",
            ArtistSettings.KEY_OTHER_LANGUAGES to false,
            ArtistSettings.KEY_FAVORITES_FIRST to false,
            ArtistSettings.KEY_ALIASES to false,
            ArtistSettings.KEY_IDENTITY_OVERRIDES to "初音未来=307078957",
            ArtistSettings.KEY_STOREFRONTS to "CN,US,JP",
            ArtistSettings.KEY_ANIMATED_HERO to false,
            ArtistSettings.KEY_VISUAL_STOREFRONT to " jp ",
            ArtistSettings.KEY_VISUAL_LANGUAGE to "CHINESE",
        ))
        assertFalse(settings.preferChineseBiography)
        assertFalse(settings.favoritesFirst)
        assertFalse(settings.identityPolicy.enabled)
        assertEquals("初音未来=307078957", settings.artistIdentityOverrides)
        assertEquals(listOf("JP"), settings.identityPolicy.storefrontOrder)
        assertFalse(settings.animatedHero)
        assertEquals("JP", settings.visualPolicy.country)
        assertTrue(settings.visualPolicy.automaticLanguage)
        assertEquals("JP", settings.languagePolicy.storefronts)
        assertFalse(artistSettingsFromValues(mapOf(ArtistSettings.KEY_LANGUAGE to "ja-JP")).preferChineseBiography)
    }

    @Test
    fun invalidPreferenceTypesAndUnknownLanguageFallBackSafely() {
        val settings = artistSettingsFromValues(mapOf(
            ArtistSettings.KEY_LANGUAGE to "unknown",
            ArtistSettings.KEY_OTHER_LANGUAGES to "false",
            ArtistSettings.KEY_FAVORITES_FIRST to 0,
            ArtistSettings.KEY_ALIASES to "false",
            ArtistSettings.KEY_IDENTITY_OVERRIDES to listOf("not a string"),
            ArtistSettings.KEY_STOREFRONTS to 123,
            ArtistSettings.KEY_ANIMATED_HERO to "false",
            ArtistSettings.KEY_VISUAL_STOREFRONT to "../../evil",
            ArtistSettings.KEY_VISUAL_LANGUAGE to "unknown",
        ))
        assertTrue(settings.preferChineseBiography)
        assertTrue(settings.favoritesFirst)
        assertTrue(settings.recognizeAliases)
        assertEquals("", settings.artistIdentityOverrides)
        assertEquals(listOf("US", "JP", "CN"), settings.identityPolicy.storefrontOrder)
        assertTrue(settings.animatedHero)
        assertEquals("US", settings.visualPolicy.country)
        assertTrue(settings.visualPolicy.automaticLanguage)
    }

    @Test
    fun newChoicesOverrideConflictingLegacyValuesAndKeepChineseIndependentOfHero() {
        val settings = artistSettingsFromValues(mapOf(
            ArtistSettings.KEY_REGION_ORDER to " jp,us,jp ",
            ArtistSettings.KEY_PREFER_CHINESE to false,
            ArtistSettings.KEY_LANGUAGE to "CHINESE",
            ArtistSettings.KEY_VISUAL_STOREFRONT to "CN",
            ArtistSettings.KEY_STOREFRONTS to "DE",
        ))
        assertEquals("JP,US", settings.storefrontOrder)
        assertEquals(listOf("JP", "US"), settings.identityPolicy.storefrontOrder)
        assertEquals(listOf("JP", "US"), settings.visualPolicy.countries)
        assertEquals("JP,US", settings.languagePolicy.storefronts)
        assertFalse(settings.preferChineseBiography)
        assertEquals(settings.visualPolicy, settings.copy(preferChineseBiography = true).visualPolicy)
    }

    @Test
    fun oldLookupOrderSurvivesWhenHeroWasLeftAtItsDefault() {
        val settings = artistSettingsFromValues(mapOf(
            ArtistSettings.KEY_VISUAL_STOREFRONT to "US,JP,CN",
            ArtistSettings.KEY_STOREFRONTS to "CN,JP",
            ArtistSettings.KEY_OTHER_LANGUAGES to false,
        ))
        assertEquals("CN,JP", settings.storefrontOrder)
        assertTrue(settings.preferChineseBiography)
        assertEquals("CN,JP", settings.languagePolicy.storefronts)
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    @Test
    fun migrationPersistsTheDisplayedControlsAndDoesNotReapplyOldChoices() {
        val helper = MigrationConfig(mapOf(
            ArtistSettings.KEY_VISUAL_STOREFRONT to "JP,CN",
            ArtistSettings.KEY_LANGUAGE to "JAPANESE",
            ArtistSettings.KEY_MOTION_RESOLUTION to "UHD",
        ))
        ArtistSettings(helper).use { settings ->
            assertEquals("JP,CN", helper.values[ArtistSettings.KEY_REGION_ORDER])
            assertEquals(false, helper.values[ArtistSettings.KEY_PREFER_CHINESE])
            assertEquals(MotionResolution.UHD, settings.read().motionResolution)
        }
        helper.set(ArtistSettings.KEY_REGION_ORDER, "US,JP,CN")
        helper.set(ArtistSettings.KEY_PREFER_CHINESE, true)
        helper.save()
        ArtistSettings(helper).use { settings ->
            assertEquals("US,JP,CN", settings.read().storefrontOrder)
            assertTrue(settings.read().preferChineseBiography)
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    @Test
    fun failedMigrationReloadsTheStoredConfigAndStillReadsTheLegacyChoices() {
        val helper = MigrationConfig(mapOf(ArtistSettings.KEY_VISUAL_STOREFRONT to "JP"), saveResult = false)
        ArtistSettings(helper).use { settings ->
            assertFalse(helper.values.containsKey(ArtistSettings.KEY_REGION_ORDER))
            assertFalse(helper.values.containsKey(ArtistSettings.KEY_PREFER_CHINESE))
            assertEquals("JP", settings.read().storefrontOrder)
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    @Test
    fun unavailableSaveAndReloadKeepTheEffectiveChoicesAndLegacyValuesConsistent() {
        val legacy = mapOf<String, Any>(
            ArtistSettings.KEY_VISUAL_STOREFRONT to "JP",
            ArtistSettings.KEY_LANGUAGE to "ENGLISH",
            ArtistSettings.KEY_MOTION_RESOLUTION to "UHD",
        )
        val helper = MigrationConfig(legacy, saveResult = false, reloadResult = false)
        ArtistSettings(helper).use { settings ->
            assertEquals("JP", settings.read().storefrontOrder)
            assertFalse(settings.read().preferChineseBiography)
            assertEquals(MotionResolution.UHD, settings.read().motionResolution)
            legacy.forEach { (key, value) -> assertEquals(value, helper.values[key]) }
            assertEquals(settings.read().storefrontOrder, helper.values[ArtistSettings.KEY_REGION_ORDER])
            assertEquals(settings.read().preferChineseBiography, helper.values[ArtistSettings.KEY_PREFER_CHINESE])
        }
    }

    @Test
    fun emptyProfilesUseLibraryInfoAndForeignProfilesRemainRealInfo() {
        assertTrue(showLibraryArtistInfo(null))
        assertTrue(showLibraryArtistInfo(AmArtistDetails()))
        assertTrue(showLibraryArtistInfo(AmArtistDetails(biography = "  ", genres = listOf(" "))))
        assertFalse(showLibraryArtistInfo(AmArtistDetails(biography = "A genuine English biography.")))
        assertFalse(showLibraryArtistInfo(AmArtistDetails(born = "1989年12月13日")))
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    private class MigrationConfig(initial: Map<String, Any>, private val saveResult: Boolean = true,
                                  private val reloadResult: Boolean = true) : ConfigHelper {
        val values = initial.toMutableMap()
        private var stored = initial.toMap()
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: String, defaultValue: T): T = (values[key] ?: defaultValue) as T
        override fun set(key: String, value: Any) { values[key] = value }
        override fun save(): Boolean { if (saveResult) stored = values.toMap(); return saveResult }
        override fun reload(): Boolean {
            if (reloadResult) { values.clear(); values.putAll(stored) }
            return reloadResult
        }
        override fun getConfigPath(): Path = Path.of("build", "migration-test-config-not-written.json")
    }
}
