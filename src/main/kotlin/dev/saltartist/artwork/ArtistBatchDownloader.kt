package dev.saltartist.artwork

import androidx.compose.runtime.mutableStateOf
import dev.saltartist.am.artistIdentityKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

enum class ArtistDownloadResult { COMPLETE, UNAVAILABLE, RETRY }

data class ArtistDownloadProgress(
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val cancelled: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val available: Int = 0,
    val unavailable: Int = 0,
    val retry: Int = 0,
    val currentArtist: String = "",
)

/** Only one background artist is queued at a time, leaving room for page requests. */
class ArtistBatchDownloader : AutoCloseable {
    private val lock = Any()
    private val state = mutableStateOf(ArtistDownloadProgress())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "salt-artist-download-all").apply { isDaemon = true }
    }
    private var stopped = false
    private var closed = false
    val progress: ArtistDownloadProgress get() = state.value

    fun start(names: List<String>, download: (String) -> CompletableFuture<ArtistDownloadResult>): Boolean = synchronized(lock) {
        if (closed || progress.running) return@synchronized false
        val artists = names.map(String::trim).filter(String::isNotEmpty).distinctBy(::artistIdentityKey)
        if (artists.isEmpty()) return@synchronized false
        stopped = false
        state.value = ArtistDownloadProgress(running = true, total = artists.size)
        worker.execute {
            try {
                for (artist in artists) {
                    synchronized(lock) {
                        if (stopped) return@execute
                        state.value = progress.copy(currentArtist = artist)
                    }
                    val result = try { download(artist).get() }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@execute }
                    catch (_: Exception) { ArtistDownloadResult.RETRY }
                    synchronized(lock) {
                        state.value = progress.copy(
                            completed = progress.completed + 1,
                            available = progress.available + if (result == ArtistDownloadResult.COMPLETE) 1 else 0,
                            unavailable = progress.unavailable + if (result == ArtistDownloadResult.UNAVAILABLE) 1 else 0,
                            retry = progress.retry + if (result == ArtistDownloadResult.RETRY) 1 else 0,
                        )
                    }
                }
            } finally {
                synchronized(lock) {
                    state.value = progress.copy(running = false, cancelling = false, cancelled = stopped, currentArtist = "")
                }
            }
        }
        true
    }

    /** An in-flight artist can finish; cancellation never deletes completed files. */
    fun cancel() = synchronized(lock) {
        if (progress.running) {
            stopped = true
            state.value = progress.copy(cancelling = true)
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        cancel()
        worker.shutdownNow()
        Unit
    }
}
