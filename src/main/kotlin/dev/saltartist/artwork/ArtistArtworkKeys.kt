package dev.saltartist.artwork

import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.am.ArtistLanguagePolicy
import dev.saltartist.am.ArtistVisualPolicy
import dev.saltartist.data.normalizeArtistKey

/** Matching preferences participate in both successful and negative image caches. */
internal fun appleArtworkCacheKey(name: String, language: ArtistLanguagePolicy, identity: ArtistIdentityPolicy,
                                  visual: ArtistVisualPolicy? = null): String =
    "${normalizeArtistKey(name)}|${identity.artistCacheKey(name)}|${language.token}|${visual?.token ?: "legacy"}"

/** Safe for the Windows thumbnail filename as well as the in-memory worker key. */
internal fun artistAvatarCacheKey(name: String, identity: ArtistIdentityPolicy): String =
    identity.artistCacheKey(name)
