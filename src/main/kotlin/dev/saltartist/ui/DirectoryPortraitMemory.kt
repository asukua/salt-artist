package dev.saltartist.ui

/** Own references only while the card is composed, independently of bounded cache eviction. */
internal class DirectoryPortraitMemory<T> {
    private var avatar: T? = null
    private var cover: T? = null

    fun resolve(currentAvatar: T?, fallback: () -> T?): T? {
        if (currentAvatar != null) avatar = currentAvatar
        // Once Apple artwork is ready, a temporary cache miss must not downgrade to a cover.
        return avatar ?: (fallback()?.also { cover = it } ?: cover)
    }
}
