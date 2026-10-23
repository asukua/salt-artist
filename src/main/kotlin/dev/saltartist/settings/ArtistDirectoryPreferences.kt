package dev.saltartist.settings

import dev.saltartist.am.artistIdentityKey
import java.util.Base64

enum class ArtistDirectoryView(val label: String) {
    GRID("头像网格"), PORTRAITS("纯头像"), LIST("头像列表"), COMPACT("紧凑列表")
}
enum class ArtistDirectorySize(val label: String, val scale: Float) {
    SMALL("小", 0.78f), MEDIUM("中", 1f), LARGE("大", 1.25f)
}
enum class ArtistDirectorySort(val label: String) {
    NAME("名称 A–Z"), NAME_DESC("名称 Z–A"), ADDED("最近添加"),
    TRACKS("歌曲最多"), ALBUMS("专辑最多"), NATIVE("原始顺序")
}
data class ArtistDirectoryPreferences(
    val view: ArtistDirectoryView = ArtistDirectoryView.GRID,
    val size: ArtistDirectorySize = ArtistDirectorySize.MEDIUM,
    val sort: ArtistDirectorySort = ArtistDirectorySort.NAME,
    val hidden: Set<String> = emptySet(),
) {
    private val hiddenKeys by lazy { hidden.mapTo(hashSetOf(), ::artistIdentityKey) }
    fun isHidden(name: String): Boolean = artistIdentityKey(name) in hiddenKeys
}

internal fun encodeHiddenArtists(names: Set<String>): String = names.joinToString("\n") {
    Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
}
internal fun decodeHiddenArtists(encoded: String): Set<String> = encoded.lineSequence().mapNotNull {
    runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8).takeIf(String::isNotBlank) }.getOrNull()
}.toSet()
