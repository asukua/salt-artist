package dev.saltartist.artwork

import dev.saltartist.settings.MotionResolution
import java.io.File
import java.util.concurrent.CompletableFuture

internal data class PreparedArtistDownload(
    val result: ArtistDownloadResult,
    val motionUrl: String? = null,
)

/** Compose video caching after static work without keeping the Apple request worker occupied. */
internal fun finishArtistDownload(
    prepared: CompletableFuture<PreparedArtistDownload>,
    quality: MotionResolution,
    downloadMotion: (String, MotionResolution) -> CompletableFuture<File?>,
    isCurrent: () -> Boolean,
): CompletableFuture<ArtistDownloadResult> = prepared.thenCompose { artist ->
    if (!isCurrent()) CompletableFuture.completedFuture(ArtistDownloadResult.RETRY)
    else artist.motionUrl?.let { url ->
        downloadMotion(url, quality).handle { movie, error ->
            if (error == null && isCurrent() && artist.result == ArtistDownloadResult.COMPLETE &&
                movie != null && movie.isFile && movie.length() > 0L)
                ArtistDownloadResult.COMPLETE else ArtistDownloadResult.RETRY
        }
    } ?: CompletableFuture.completedFuture(artist.result)
}.exceptionally { ArtistDownloadResult.RETRY }
