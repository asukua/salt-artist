package dev.saltartist.am

enum class PreferredArtistLanguage(val code: String) {
    CHINESE("zh-Hans"), ENGLISH("en-US"), JAPANESE("ja-JP");
}

data class ArtistLanguagePolicy(
    val preferredLanguage: PreferredArtistLanguage = PreferredArtistLanguage.CHINESE,
    val showOtherLanguages: Boolean = true,
)

data class AvatarMetadata(val artistId: String, val template: String, val crop: String = "bb")

enum class AmMotionKind { HLS, MP4 }

/** The movie and poster are from the same variant of the matched artist header. */
data class AmArtistMotion(
    val url: String,
    val kind: AmMotionKind,
    val posterTemplate: String? = null,
    val posterCrop: String = "bb",
)

/** Values are supplied by the matched Apple artist page; absent facts stay absent. */
data class AmArtistDetails(
    val biography: String? = null,
    val born: String? = null,
    val formed: String? = null,
    val origin: String? = null,
    val genres: List<String> = emptyList(),
    val sourceUrl: String? = null,
    /** Actual biography language; legacy v2 records are migrated as Chinese by the catalog. */
    val languageCode: String? = null,
) {
    val hasContent: Boolean
        get() = !biography.isNullOrBlank() || !born.isNullOrBlank() || !formed.isNullOrBlank() ||
            !origin.isNullOrBlank() || genres.any { it.isNotBlank() }
}

/** Public artwork templates use the `{w}x{h}{c}.{f}` placeholders. */
data class AmArtist(
    val artistId: String,
    val heroTemplate: String?,
    val heroCrop: String,
    val heroIsWide: Boolean,
    val logoTemplate: String?,
    val logoCrop: String,
    val fetchedAt: Long,
    val details: AmArtistDetails? = null,
    val avatarTemplate: String? = null,
    val avatarCrop: String = "bb",
    /** Zero means this language still needs an explicit page/profile attempt. */
    val detailsFetchedAt: Long = 0L,
    val motion: AmArtistMotion? = null,
    /** Zero marks legacy or avatar-only metadata that has not checked header motion. */
    val motionCheckedAt: Long = 0L,
)
