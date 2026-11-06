package dev.saltartist.ui

import kotlin.test.*

class ArtistCatalogLayoutTest {
    @Test fun preservesTheOriginalColumnMajorOrderingAcrossWidthsAndExpansion() {
        for (count in listOf(0, 1, 2, 3, 4, 8, 9, 10, 127, 129, 513, 5000)) {
            for (columns in 1..3) for (expanded in listOf(false, true)) {
                val layout = CatalogSongLayout(count, columns, expanded)
                val shown = if (expanded) (0 until count).toList() else (0 until minOf(count, columns * 3)).toList()
                val expectedRows = if (expanded) ((shown.size + columns - 1) / columns).coerceAtLeast(1) else 3
                val oldColumns = shown.chunked(expectedRows)
                val newColumns = (0 until layout.columns).map { column ->
                    (0 until layout.rows).mapNotNull { layout.index(it, column) }
                }
                assertEquals(oldColumns, newColumns, "count=$count columns=$columns expanded=$expanded")
                for (column in oldColumns.indices) for (row in 0 until layout.rows) {
                    assertEquals(row < oldColumns[column].lastIndex, layout.divider(row, column))
                }
                assertTrue(layout.overviewRows <= 3)
            }
        }
    }

    @Test fun largeCataloguesExposeEverySongAsRowsInsteadOfOneGiantItem() {
        val layout = CatalogSongLayout(10000, 3, true)
        assertEquals(3334, layout.rows)
        assertEquals(3331, layout.remainingRows)
        val visited = (0 until layout.rows).flatMap { row -> (0 until layout.columns).mapNotNull { layout.index(row, it) } }
        assertEquals((0 until 10000).toSet(), visited.toSet())
        assertEquals(10000, visited.size)
    }
}
