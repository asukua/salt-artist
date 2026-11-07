package dev.saltartist.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.bridge.NativeTrackMenuBridge
import dev.saltartist.data.ArtistTrack
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/** Native song actions share the host Track identity; older hosts retain a basic context menu. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TrackContextArea(
    track: ArtistTrack,
    deps: ArtistScreenDeps,
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) { content(); return }
    val bridge = remember(deps) {
        NativeTrackMenuBridge(deps.log, deps.directoryTargets?.screenClass == "androidx.compose.ui.\u0998")
    }
    val original = deps.repository.nativeTracks[track.id]
    val nativeMenu = remember(bridge, original) { original?.let(bridge::prepare) }
    val navigator = deps.navigation.navigator()
    fun action(name: String, work: () -> Unit) {
        runCatching(work).onFailure { deps.log.log("song menu $name failed: ${deps.log.describe(it)}") }
    }
    ContextMenuArea(items = {
        buildList {
            add(ContextMenuItem("播放", onPlay))
            add(ContextMenuItem("打开专辑") {
                action("album") { deps.navigation.openAlbum(navigator, track.album, track.albumArtist) }
            })
            if (track.path.isNotBlank()) add(ContextMenuItem("打开所在文件夹") {
                action("folder") {
                    val folder = File(track.path).absoluteFile.parentFile
                    if (folder?.isDirectory == true && Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder)
                }
            })
            add(ContextMenuItem("复制歌曲信息") {
                action("copy") {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection(listOf(track.title, track.artist, track.album, track.path).filter(String::isNotBlank).joinToString("\n")), null)
                }
            })
        }
    }) {
        Box(Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (event.changes.none { it.isConsumed } && event.buttons.isSecondaryPressed && nativeMenu?.open() == true) {
                event.changes.forEach { it.consume() }
            }
        }) {
            content()
            nativeMenu?.Content()
        }
    }
}
