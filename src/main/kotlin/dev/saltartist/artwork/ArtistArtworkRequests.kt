package dev.saltartist.artwork

/** Coordinates refresh with late blocking requests, including their cache publication. */
internal class ArtistArtworkRequests {
    private val generations = HashMap<String, Long>()
    private var nextGeneration = 0L
    private var resetGeneration = 0L

    @Synchronized
    fun generation(artist: String): Long = generations[artist] ?: resetGeneration

    @Synchronized
    fun invalidate(artist: String, clear: () -> Unit) {
        generations[artist] = ++nextGeneration
        clear()
    }

    @Synchronized
    fun clear(clear: () -> Unit) {
        resetGeneration = ++nextGeneration
        generations.clear()
        clear()
    }

    @Synchronized
    fun publish(artist: String, expectedGeneration: Long, update: () -> Unit): Boolean {
        if (generation(artist) != expectedGeneration) return false
        update()
        return true
    }
}
