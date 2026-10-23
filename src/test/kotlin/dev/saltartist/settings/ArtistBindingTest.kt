package dev.saltartist.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.nio.file.Path

class ArtistBindingTest {
    @OptIn(UnstableSpwWorkshopApi::class)
    @Test
    fun aFailedSaveDoesNotPublishOrKeepAnUnpersistedBinding() {
        val helper = BindingConfig(false)
        ArtistSettings(helper).use { settings ->
            assertFalse(settings.setArtistBinding("Artist", "42"))
            assertEquals(null, settings.artistBinding("Artist"))
            assertEquals("", helper.values[ArtistSettings.KEY_IDENTITY_OVERRIDES])
            assertFalse(settings.read().recognizeAliases)
            assertEquals(false, helper.values[ArtistSettings.KEY_ALIASES])
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    @Test
    fun selectingAnArtistPersistsAndEnablesMatchingWhileResetKeepsTheSwitch() {
        val helper = BindingConfig(true)
        ArtistSettings(helper).use { settings ->
            assertTrue(settings.setArtistBinding("Artist", "42"))
            assertEquals("42", settings.artistBinding("Artist"))
            assertEquals("Artist=42", helper.values[ArtistSettings.KEY_IDENTITY_OVERRIDES])
            assertTrue(settings.read().recognizeAliases)
            assertTrue(settings.setArtistBinding("Artist", null))
            assertEquals(null, settings.artistBinding("Artist"))
            assertTrue(settings.read().recognizeAliases)
        }
    }

    @Test
    fun replacesOnlyTheSelectedArtistAndKeepsOtherBindings() {
        assertEquals("周杰伦=300117743;Other=44;Hatsune Miku=307078957", withArtistBinding(
            "周杰伦=300117743;Hatsune Miku=9\nOther=44; HATSUNE MIKU=10", "Hatsune Miku", "307078957",
        ))
    }

    @Test
    fun resetDeletesAllConflictingEntriesForThisArtist() {
        assertEquals("Other=44", withArtistBinding("AC/DC=1;Other=44;ac/dc=2", "AC/DC", null))
        assertEquals("", withArtistBinding("AC/DC=1", "AC/DC", null))
    }

    @Test
    fun preservesPunctuationAndUnrelatedInvalidConfigurationForReview() {
        assertEquals("AC DC=2;invalid;AC/DC=3", withArtistBinding("AC DC=2;invalid", "AC/DC", "3"))
    }

    @Test
    fun rejectsInvalidIdsAndNamesThatCannotBeSerialized() {
        listOf("0", "-1", "bad", "https://unrelated.example/123").forEach { id ->
            assertFailsWith<IllegalArgumentException> { withArtistBinding("", "Artist", id) }
        }
        listOf("", "A;B", "A=B", "A\nB").forEach { name ->
            assertFailsWith<IllegalArgumentException> { withArtistBinding("", name, "123") }
        }
    }

    @OptIn(UnstableSpwWorkshopApi::class)
    private class BindingConfig(private val saveResult: Boolean) : ConfigHelper {
        val values = mutableMapOf<String, Any>(ArtistSettings.KEY_ALIASES to false)
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: String, defaultValue: T): T = (values[key] ?: defaultValue) as T
        override fun set(key: String, value: Any) { values[key] = value }
        override fun save(): Boolean = saveResult
        override fun reload(): Boolean = true
        override fun getConfigPath(): Path = Path.of("build", "binding-test-config-not-written.json")
    }
}
