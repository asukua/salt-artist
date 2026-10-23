package dev.saltartist.settings

import dev.saltartist.am.AmArtistDetails
import dev.saltartist.am.PreferredArtistLanguage
import dev.saltartist.ui.showLibraryArtistInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistSettingsTest {
    @Test
    fun missingPreferencesKeepChineseFirstAndAllowOtherLanguages() {
        val settings = artistSettingsFromValues(emptyMap())
        assertEquals(PreferredArtistLanguage.CHINESE, settings.preferredLanguage)
        assertTrue(settings.showOtherLanguages)
        assertTrue(settings.favoritesFirst)
        assertTrue(settings.recognizeAliases)
        assertEquals("", settings.artistIdentityOverrides)
        assertEquals(listOf("US", "JP", "CN"), settings.identityPolicy.storefrontOrder)
        assertTrue(settings.animatedHero)
    }

    @Test
    fun selectedLanguageAndDisabledFeaturesAreKept() {
        val settings = artistSettingsFromValues(mapOf(
            ArtistSettings.KEY_LANGUAGE to "ENGLISH",
            ArtistSettings.KEY_OTHER_LANGUAGES to false,
            ArtistSettings.KEY_FAVORITES_FIRST to false,
            ArtistSettings.KEY_ALIASES to false,
            ArtistSettings.KEY_IDENTITY_OVERRIDES to "初音未来=307078957",
            ArtistSettings.KEY_STOREFRONTS to "CN,US,JP",
            ArtistSettings.KEY_ANIMATED_HERO to false,
        ))
        assertEquals(PreferredArtistLanguage.ENGLISH, settings.languagePolicy.preferredLanguage)
        assertFalse(settings.languagePolicy.showOtherLanguages)
        assertFalse(settings.favoritesFirst)
        assertFalse(settings.identityPolicy.enabled)
        assertEquals("初音未来=307078957", settings.artistIdentityOverrides)
        assertEquals(listOf("CN", "US", "JP"), settings.identityPolicy.storefrontOrder)
        assertFalse(settings.animatedHero)
        assertEquals(PreferredArtistLanguage.JAPANESE,
            artistSettingsFromValues(mapOf(ArtistSettings.KEY_LANGUAGE to "ja-JP")).preferredLanguage)
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
        ))
        assertEquals(PreferredArtistLanguage.CHINESE, settings.preferredLanguage)
        assertTrue(settings.showOtherLanguages)
        assertTrue(settings.favoritesFirst)
        assertTrue(settings.recognizeAliases)
        assertEquals("", settings.artistIdentityOverrides)
        assertEquals(listOf("US", "JP", "CN"), settings.identityPolicy.storefrontOrder)
        assertTrue(settings.animatedHero)
    }

    @Test
    fun emptyProfilesUseLibraryInfoAndForeignProfilesRemainRealInfo() {
        assertTrue(showLibraryArtistInfo(null))
        assertTrue(showLibraryArtistInfo(AmArtistDetails()))
        assertTrue(showLibraryArtistInfo(AmArtistDetails(biography = "  ", genres = listOf(" "))))
        assertFalse(showLibraryArtistInfo(AmArtistDetails(biography = "A genuine English biography.")))
        assertFalse(showLibraryArtistInfo(AmArtistDetails(born = "1989年12月13日")))
    }
}
