package dev.saltartist.am

enum class PreferredArtistLanguage(val code: String) {
    CHINESE("zh-Hans"), ENGLISH("en-US"), JAPANESE("ja-JP"),
    TRADITIONAL_CHINESE("zh-Hant"), KOREAN("ko-KR"), FRENCH("fr-FR"), GERMAN("de-DE"),
    SPANISH("es-ES"), ITALIAN("it-IT"), PORTUGUESE("pt-BR"), DUTCH("nl-NL");
}

data class ArtistLanguagePolicy(
    val preferredLanguage: PreferredArtistLanguage = PreferredArtistLanguage.CHINESE,
    val showOtherLanguages: Boolean = true,
    /** Null keeps the former language-only API; settings use the ordered regional policy. */
    val storefronts: String? = null,
    val preferChineseBiography: Boolean = true,
) {
    val token: String get() = if (storefronts == null) "legacy-${preferredLanguage.code}-$showOtherLanguages"
        else "native-v1-${artistStorefrontOrder(storefronts).joinToString("-")}-$preferChineseBiography"
    internal val nativeAttempts: List<ArtistPageLocale> get() = artistStorefrontOrder(storefronts.orEmpty()).map(::nativeArtistLocale)
    internal val regionalAttempts: List<ArtistPageLocale> get() {
        val native = nativeAttempts
        val chinese = if (preferChineseBiography) (listOf("CN") + native.map { it.country })
            .map { ArtistPageLocale(it, PreferredArtistLanguage.CHINESE) } else emptyList()
        return (chinese + native).distinct()
    }
}

/** One page request and its parser language; regional variants remain separate cache records. */
internal data class ArtistPageLocale(
    val country: String,
    val language: PreferredArtistLanguage,
    val pageLanguageCode: String = language.code,
) {
    val token: String get() = "$country-$pageLanguageCode"
}

/** Common Apple storefront languages; other regions use an explicit, stable English fallback. */
internal fun nativeArtistLocale(country: String): ArtistPageLocale = when (country) {
    "CN" -> ArtistPageLocale(country, PreferredArtistLanguage.CHINESE)
    "TW" -> ArtistPageLocale(country, PreferredArtistLanguage.TRADITIONAL_CHINESE, "zh-Hant-TW")
    "HK", "MO" -> ArtistPageLocale(country, PreferredArtistLanguage.TRADITIONAL_CHINESE, "zh-Hant-HK")
    "JP" -> ArtistPageLocale(country, PreferredArtistLanguage.JAPANESE)
    "KR" -> ArtistPageLocale(country, PreferredArtistLanguage.KOREAN)
    "FR" -> ArtistPageLocale(country, PreferredArtistLanguage.FRENCH)
    "DE", "AT" -> ArtistPageLocale(country, PreferredArtistLanguage.GERMAN)
    "ES" -> ArtistPageLocale(country, PreferredArtistLanguage.SPANISH)
    "IT" -> ArtistPageLocale(country, PreferredArtistLanguage.ITALIAN)
    "BR" -> ArtistPageLocale(country, PreferredArtistLanguage.PORTUGUESE)
    "PT" -> ArtistPageLocale(country, PreferredArtistLanguage.PORTUGUESE, "pt-PT")
    "NL" -> ArtistPageLocale(country, PreferredArtistLanguage.DUTCH)
    "GB", "IE" -> ArtistPageLocale(country, PreferredArtistLanguage.ENGLISH, "en-GB")
    "AU" -> ArtistPageLocale(country, PreferredArtistLanguage.ENGLISH, "en-AU")
    "CA" -> ArtistPageLocale(country, PreferredArtistLanguage.ENGLISH, "en-CA")
    "NZ" -> ArtistPageLocale(country, PreferredArtistLanguage.ENGLISH, "en-NZ")
    else -> ArtistPageLocale(country, PreferredArtistLanguage.ENGLISH)
}

internal fun artistStorefrontOrder(value: String): List<String> = value.split(Regex("[,;，；\\s]+"))
    .map { it.trim().uppercase(java.util.Locale.ROOT) }
    .filter { it.length == 2 && it in ARTIST_ISO_COUNTRIES }
    .distinct().take(8).ifEmpty { ArtistVisualPolicy.DEFAULT_STOREFRONTS.split(',') }

private val ARTIST_ISO_COUNTRIES = java.util.Locale.getISOCountries().toSet()
internal val LEGACY_ARTIST_LANGUAGES = listOf(PreferredArtistLanguage.CHINESE, PreferredArtistLanguage.ENGLISH,
    PreferredArtistLanguage.JAPANESE)

internal fun artistAcceptLanguage(url: String): String {
    val code = Regex("(?:[?&])l=([A-Za-z-]+)").find(url)?.groupValues?.get(1) ?: "zh-CN"
    return "$code,${code.substringBefore('-')};q=0.9"
}

/** Independent source for the artist hero and its matching motion poster. */
data class ArtistVisualPolicy(
    val storefront: String = DEFAULT_STOREFRONTS,
    val language: PreferredArtistLanguage = PreferredArtistLanguage.ENGLISH,
    /** Explicit language is retained only for the former API; settings enable native regional pages. */
    val automaticLanguage: Boolean = false,
) {
    /** Preserve explicit ordering; unknown and repeated regions are ignored. */
    val countries: List<String> = artistStorefrontOrder(storefront)
    internal val locales: List<ArtistPageLocale> get() = countries.map {
        if (automaticLanguage) nativeArtistLocale(it) else ArtistPageLocale(it, language)
    }
    val country: String get() = countries.first()
    val token: String get() = if (automaticLanguage) "auto-v1-${locales.joinToString("_") { it.token }}"
        else "${countries.joinToString("-")}-${language.code}"

    companion object {
        const val DEFAULT_STOREFRONTS = "US,JP,CN"
    }
}

data class AvatarMetadata(val artistId: String, val template: String, val crop: String = "bb")

enum class DownloadResolutionStatus { FOUND, NOT_FOUND, RETRY }

/** Metadata fetch failure is separate from an authoritative identity mismatch. */
data class DownloadResolution(val artist: AmArtist?, val status: DownloadResolutionStatus)

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
) {
    /** A usable selection can be downloaded while higher-priority sources still need refresh. */
    internal val hasDownloadContent: Boolean
        get() = !heroTemplate.isNullOrBlank() || !avatarTemplate.isNullOrBlank() ||
            !logoTemplate.isNullOrBlank() || details?.hasContent == true || motion != null
}
