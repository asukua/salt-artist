package dev.saltartist.ui

/** Three-row song shelves page horizontally, preserving the library's column-major order. */
internal data class CatalogSongLayout(val count: Int, val maxColumns: Int, val requestedPage: Int = 0) {
    init { require(count >= 0 && maxColumns > 0) }
    val rows = 3
    val pageSize = maxColumns * rows
    val pageCount = if (count == 0) 1 else (count - 1) / pageSize + 1
    val page = requestedPage.coerceIn(0, pageCount - 1)
    val startIndex = page * pageSize
    val shownCount = minOf(count - startIndex, pageSize)
    val columns = if (shownCount == 0) 0 else (shownCount - 1) / rows + 1
    val hasPrevious = page > 0
    val hasNext = page < pageCount - 1
    fun index(row: Int, column: Int): Int? = (startIndex + column * rows + row).takeIf {
        row in 0 until rows && column in 0 until columns && it < startIndex + shownCount
    }
    fun divider(row: Int, column: Int): Boolean = index(row + 1, column) != null
}
