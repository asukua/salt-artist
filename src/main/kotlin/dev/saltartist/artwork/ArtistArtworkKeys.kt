package dev.saltartist.artwork

import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.am.ArtistLanguagePolicy
import dev.saltartist.data.normalizeArtistKey

/** Matching preferences participate in both successful and negative image caches. */
internal fun appleArtworkCacheKey(name: String, language: ArtistLanguagePolicy, identity: ArtistIdentityPolicy): String =
    "${normalizeArtistKey(name)}|${identity.artistCacheKey(name)}|${identity.cacheToken}|${language.preferredLanguage.code}|${language.showOtherLanguages}"

/** Safe for the Windows thumbnail filename as well as the in-memory worker key. */
internal fun artistAvatarCacheKey(name: String, identity: ArtistIdentityPolicy): String =
    "${identity.artistCacheKey(name)}-${identity.cacheToken}"
