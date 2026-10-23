package dev.saltartist.am

import java.util.Base64

/** The first eight columns remain compatible with the image-only cache format. */
internal object AppleMusicCache {
    private const val VERSION = "2"
    private const val MOTION_VERSION = "4"

    fun decode(line: String): Pair<String, AmArtist>? = runCatching {
        if (line.length > 256_000) return null
        val fields = line.split('\t')
        if (fields.size < 8 || fields[0].isBlank() || !fields[1].matches(Regex("[0-9]+"))) return null
        val motionVersion = fields.size >= 24 && fields[8] == MOTION_VERSION
        val localized = fields.size >= 19 && (fields[8] == "3" || motionVersion)
        val extended = fields.size >= 18 && (fields[8] == VERSION || localized)
        val languageCode = if (localized) fields[18].takeIf { code -> PreferredArtistLanguage.entries.any { it.code == code } } else null
        val language = PreferredArtistLanguage.entries.firstOrNull { it.code == languageCode } ?: PreferredArtistLanguage.CHINESE
        val biography = if (localized) AppleMusicPageParser.localizedBiography(unpack(fields[12]), language, false)?.first
            else if (extended) AppleMusicPageParser.chineseBiography(unpack(fields[12])) else null
        val details = if (extended) AmArtistDetails(
            biography = biography,
            born = AppleMusicPageParser.formatDate(unpack(fields[13])),
            formed = AppleMusicPageParser.formatDate(unpack(fields[14])),
            origin = AppleMusicPageParser.localizedPlace(unpack(fields[15]), if (localized) PreferredArtistLanguage.ENGLISH else language),
            genres = unpack(fields[16])?.split('\n')?.mapNotNull {
                AppleMusicPageParser.localizedGenre(it, if (localized) PreferredArtistLanguage.ENGLISH else language)
            }.orEmpty(),
            sourceUrl = unpack(fields[17])?.takeIf { AppleMusicPageParser.artistIdOf(it) == fields[1] },
            languageCode = languageCode,
        ).takeIf { it.hasContent } else null
        fields[0] to AmArtist(
            artistId = fields[1],
            heroTemplate = nullable(fields[2]),
            heroCrop = fields[3].ifEmpty { "bb" },
            heroIsWide = fields[4] == "1",
            logoTemplate = nullable(fields[5]),
            logoCrop = fields[6].ifEmpty { "bb" },
            fetchedAt = fields[7].toLongOrNull() ?: 0L,
            details = details,
            avatarTemplate = if (extended) nullable(fields[9]) else null,
            avatarCrop = if (extended) fields[10].ifEmpty { "bb" } else "bb",
            detailsFetchedAt = if (extended) fields[11].toLongOrNull() ?: 0L else 0L,
            motion = if (motionVersion) {
                val url = unpack(fields[20])?.let(AppleMusicPageParser::safeMotionUrl)
                val kind = AmMotionKind.entries.firstOrNull { it.name == fields[19] }
                if (url != null && kind == AppleMusicPageParser.motionKind(url)) AmArtistMotion(
                    url, checkNotNull(kind), unpack(fields[21])?.let(AppleMusicPageParser::safeImageUrl),
                    fields[22].ifEmpty { "bb" },
                ) else null
            } else null,
            motionCheckedAt = if (motionVersion) fields[23].toLongOrNull()?.coerceAtLeast(0L) ?: 0L else 0L,
        )
    }.getOrNull()

    fun encode(key: String, artist: AmArtist): String = (listOf(
        key, artist.artistId, artist.heroTemplate ?: "-", artist.heroCrop,
        if (artist.heroIsWide) "1" else "0", artist.logoTemplate ?: "-", artist.logoCrop,
        artist.fetchedAt.toString(), MOTION_VERSION, artist.avatarTemplate ?: "-", artist.avatarCrop,
        artist.detailsFetchedAt.toString(), pack(artist.details?.biography), pack(artist.details?.born),
        pack(artist.details?.formed), pack(artist.details?.origin),
        pack(artist.details?.genres?.takeIf { it.isNotEmpty() }?.joinToString("\n")), pack(artist.details?.sourceUrl),
        artist.details?.languageCode ?: "-", artist.motion?.kind?.name ?: "-", pack(artist.motion?.url),
        pack(artist.motion?.posterTemplate), artist.motion?.posterCrop ?: "bb", artist.motionCheckedAt.toString(),
    )).joinToString("\t")

    fun isFresh(artist: AmArtist, now: Long, maxAgeMs: Long): Boolean =
        artist.detailsFetchedAt > 0L && artist.motionCheckedAt > 0L && now >= artist.fetchedAt &&
            now >= artist.detailsFetchedAt && now >= artist.motionCheckedAt &&
            now - artist.fetchedAt < maxAgeMs && now - artist.detailsFetchedAt < maxAgeMs &&
            now - artist.motionCheckedAt < maxAgeMs

    private fun nullable(value: String): String? = value.takeIf { it.isNotEmpty() && it != "-" }
    private fun pack(value: String?): String = value?.let {
        Base64.getEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    } ?: "-"
    private fun unpack(value: String): String? = nullable(value)?.let {
        runCatching { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }.getOrNull()
    }
}
