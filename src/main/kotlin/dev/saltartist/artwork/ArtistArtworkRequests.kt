package dev.saltartist.artwork

/** Coordinates refresh with late blocking requests, including their cache publication. */
internal class ArtistArtworkRequests {
    private val generations = HashMap<String, Long>()

    @Synchronized
    fun generation(artist: String): Long = generations[artist] ?: 0L

    @Synchronized
    fun invalidate(artist: String, clear: () -> Unit) {
        generations[artist] = generation(artist) + 1L
        clear()
    }

    @Synchronized
    fun publish(artist: String, expectedGeneration: Long, update: () -> Unit): Boolean {
        if (generation(artist) != expectedGeneration) return false
        update()
        return true
    }
}
