package dev.saltartist.ui

import dev.saltartist.am.AmArtistDetails

/** A portrait alone is not a biography; empty/hidden profiles use the local summary. */
internal fun showLibraryArtistInfo(details: AmArtistDetails?): Boolean = details?.hasContent != true
