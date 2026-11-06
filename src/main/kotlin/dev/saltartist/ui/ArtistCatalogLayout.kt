package dev.saltartist.ui

/** Column-major order matches the original catalogue, including partially filled last columns. */
internal data class CatalogSongLayout(val count: Int, val maxColumns: Int, val expanded: Boolean) {
    val shownCount = if (expanded) count else minOf(count, maxColumns * 3)
    val rows = if (expanded) ((shownCount + maxColumns - 1) / maxColumns).coerceAtLeast(1) else 3
    val columns = if (shownCount == 0) 0 else (shownCount + rows - 1) / rows
    val overviewRows = minOf(rows, 3)
    val remainingRows = (rows - overviewRows).coerceAtLeast(0)
    fun index(row: Int, column: Int): Int? = (column * rows + row).takeIf {
        row in 0 until rows && column in 0 until columns && it < shownCount
    }
    fun divider(row: Int, column: Int): Boolean = index(row + 1, column) != null
}
