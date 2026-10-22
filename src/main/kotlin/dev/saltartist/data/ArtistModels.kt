package dev.saltartist.data

import java.util.Locale
import java.text.Normalizer

data class ArtistTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val path: String,
    val genre: String,
    val year: Int,
    val number: Int,
    val durationMs: Long,
    val isFavorite: Boolean,
    /** 宿主曲库的 addedTime（毫秒）；1.19.1 源自文件创建时间，不是精确导入时刻。 */
    val libraryAddedTime: Long = 0L,
)

data class ArtistAlbum(
    val title: String,
    val artist: String,
    val year: Int,
    val tracks: List<ArtistTrack>,
) {
    val coverTrackId: String get() = tracks.firstOrNull { it.id.isNotBlank() }?.id.orEmpty()
    val trackCount: Int get() = tracks.size
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
    val latestLibraryAddedTime: Long get() = tracks.maxOfOrNull { it.libraryAddedTime.coerceAtLeast(0L) } ?: 0L
}

/** 与宿主的添加排序保持一致；时间缺失时仅按名称稳定选择，不借发行年份推断。 */
internal fun selectLatestAddedAlbum(albums: List<ArtistAlbum>): ArtistAlbum? =
    albums.minWithOrNull(
        compareByDescending<ArtistAlbum> { it.latestLibraryAddedTime }
            .thenBy { it.title }
            .thenBy { it.artist },
    )

data class ArtistPageModel(
    val name: String,
    val tracks: List<ArtistTrack>,
    val albums: List<ArtistAlbum>,
    val genres: List<String>,
    val yearRange: IntRange?,
) {
    val trackCount: Int get() = tracks.size
    val albumCount: Int get() = albums.size
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }
    val heroTrackId: String get() = tracks.firstOrNull { it.id.isNotBlank() }?.id.orEmpty()
}

/** 与宿主一致的规范化键：NFKC、小写、非字母数字折叠成下划线。 */
fun normalizeArtistKey(value: String): String {
    val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        .trim('_')
    return normalized.ifEmpty { "unknown_artist" }
}

/** 宿主用 `/` 连接多位艺人，同时兼容 `,`、`&`、`feat.` 等常见写法。 */
fun splitArtistNames(value: String): List<String> =
    value.split(Regex("\\s*[/,;&]\\s*|\\s+(?:feat|ft|featuring)\\.?\\s+", RegexOption.IGNORE_CASE))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
