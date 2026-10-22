package dev.saltartist.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ArtistDirectoryOrderingTest {
    private data class Slot(val name: String, val group: Int, val item: Int)

    @Test
    fun favoritesLeadWithoutChangingEitherPartitionsOriginalGroupAndItemOrder() {
        val source = listOf(Slot("Z", 0, 0), Slot("B", 0, 1), Slot("Y", 1, 0), Slot("A", 1, 1), Slot("X", 1, 2))
        val result = stableFavoritesFirst(source, setOf("A", "B"), true, Slot::name)
        assertEquals(listOf(source[1], source[3], source[0], source[2], source[4]), result)
        assertEquals(listOf("Z", "B", "Y", "A", "X"), source.map { it.name })
    }

    @Test
    fun preservesDuplicatesAndTheirOriginalRendererIndexMappings() {
        val source = listOf(Slot("B", 0, 0), Slot("A", 0, 1), Slot("A", 1, 0), Slot("B", 1, 1))
        val result = stableFavoritesFirst(source, setOf("a"), true, Slot::name)
        assertEquals(listOf(source[1], source[2], source[0], source[3]), result)
        assertEquals(listOf(0 to 1, 1 to 0, 0 to 0, 1 to 1), result.map { it.group to it.item })
    }

    @Test
    fun disabledAndEmptyFavoritesReturnTheOriginalListWithoutInspectingItems() {
        val source = listOf("B", "A")
        assertSame(source, stableFavoritesFirst(source, setOf("A"), false) { error("disabled must not inspect names") })
        assertSame(source, stableFavoritesFirst(source, emptySet(), true) { error("empty set must not inspect names") })
    }

    @Test
    fun alreadyPartitionedAndMissingFavoritesKeepTheOriginalListReference() {
        val source = listOf("A", "B", "Z")
        assertSame(source, stableFavoritesFirst(source, setOf("A", "B"), true) { it })
        assertSame(source, stableFavoritesFirst(source, setOf("missing"), true) { it })
        assertSame(source, stableFavoritesFirst(source, source.toSet(), true) { it })
    }

    @Test
    fun togglingTheSettingNeverAccumulatesAReorderedSource() {
        val source = listOf("Z", "B", "Y", "A")
        assertEquals(listOf("B", "A", "Z", "Y"), stableFavoritesFirst(source, setOf("A", "B"), true) { it })
        assertSame(source, stableFavoritesFirst(source, setOf("A", "B"), false) { it })
        assertEquals(listOf("Y", "Z", "B", "A"), stableFavoritesFirst(source, setOf("Y"), true) { it })
    }

    @Test
    fun favoriteNamesUseTheSameUnicodeAndPunctuationNormalizationAsTheProject() {
        val source = listOf("Other", "ＡＲＴＩＳＴ Name", "Singer-Name")
        assertEquals(
            listOf("ＡＲＴＩＳＴ Name", "Singer-Name", "Other"),
            stableFavoritesFirst(source, setOf("artist_name", "singer name"), true) { it },
        )
    }
}
