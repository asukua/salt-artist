package dev.saltartist.ui

import dev.saltartist.data.ArtistTrack
import kotlin.test.*

class ArtistCatalogLayoutTest {
    @Test fun pagesKeepEverySongExactlyOnceInOriginalColumnMajorOrder() {
        for (count in listOf(0, 1, 2, 3, 4, 8, 9, 10, 127, 129, 513, 10000)) for (columns in 1..3) {
            val first = CatalogSongLayout(count, columns)
            val visited = (0 until first.pageCount).flatMap { page ->
                val layout = CatalogSongLayout(count, columns, page)
                assertTrue(layout.shownCount <= columns * 3)
                assertEquals(3, layout.rows)
                (0 until layout.columns).flatMap { column ->
                    (0 until layout.rows).mapNotNull { row -> layout.index(row, column) }
                }
            }
            assertEquals((0 until count).toList(), visited, "count=$count columns=$columns")
        }
    }

    @Test fun pageBoundariesAndResizeClampWithoutWrappingOrLosingTheLastSong() {
        val first = CatalogSongLayout(20, 3)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        assertEquals(0, CatalogSongLayout(20, 3, -1).page)
        val last = CatalogSongLayout(20, 3, 999)
        assertEquals(2, last.page)
        assertEquals(18, last.startIndex)
        assertEquals(19, last.index(1, 0))
        assertNull(last.index(2, 0))
        assertFalse(last.hasNext)
        assertFalse(last.divider(1, 0))
        assertEquals(0, CatalogSongLayout(1, 1, last.page).page)
        assertEquals(0, CatalogSongLayout(0, 3, last.page).shownCount)
    }

    @Test fun firstPageKeepsTheOldThreeRowPreview() {
        for (columns in 1..3) {
            val layout = CatalogSongLayout(1000, columns)
            assertEquals(columns, layout.columns)
            assertEquals(0, layout.startIndex)
            assertEquals(columns * 3, layout.shownCount)
            if (columns > 1) assertEquals(3, layout.index(0, 1)) else assertNull(layout.index(0, 1))
        }
    }

    @Test fun searchUsesTheWholeLibraryAndRetainsTheOriginalTrackObjects() {
        val source = (0 until 1000).map { index ->
            ArtistTrack("id-$index", "Song $index", "Artist", "Album ${index / 10}", "Artist", "", "", 2026, index, 1000, false)
        }
        assertSame(source, filterArtistSongs(source, ""))
        val result = filterArtistSongs(source, "song 999")
        assertEquals(1, result.size)
        assertSame(source[999], result.single())
        assertEquals(source.subList(990, 1000), filterArtistSongs(source, "album 99"))
        assertTrue(filterArtistSongs(source, "missing").isEmpty())
    }
}
