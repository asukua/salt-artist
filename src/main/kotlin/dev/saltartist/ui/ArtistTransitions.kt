package dev.saltartist.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.bridge.ArtistDirectoryThemeBridge

/** Match SPW desktop navigation: stiffness 500, forward slide 150 physical pixels, reverse pop. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ArtistPageTransition(allSongs: Boolean, content: @Composable (Boolean) -> Unit) {
    AnimatedContent(allSongs, modifier = Modifier.fillMaxSize().clipToBounds(), transitionSpec = {
        if (targetState) {
            slideInVertically(spring(stiffness = 500f)) { 150 } togetherWith fadeOut(spring(stiffness = 500f))
        } else {
            fadeIn(spring(stiffness = 500f)) togetherWith
                (slideOutVertically(spring(stiffness = 500f)) { 150 } + fadeOut(spring(stiffness = 500f)))
        }
    }, label = "artist-song-page") { shown ->
        val active = shown == allSongs
        Box(Modifier.fillMaxSize()
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (!active) event.changes.forEach { it.consume() }
            }) { content(shown) }
    }
}

/** Keep modal content until fade-out completes; the outgoing overlay cannot execute actions. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ArtistOverlayTransition(visible: Boolean, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    AnimatedVisibility(state, modifier = Modifier.fillMaxSize(),
        enter = fadeIn(spring(stiffness = 500f)), exit = fadeOut(spring(stiffness = 500f))) {
        Box(Modifier.fillMaxSize()
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (!visible) event.changes.forEach { it.consume() }
            }) { content() }
    }
}

/** The native table reads the host theme; its surrounding page must use the same light/dark surface. */
@OptIn(InternalComposeApi::class)
@Composable
internal fun hostSongPalette(fallback: ArtworkPalette): ArtworkPalette {
    val theme = remember { ArtistDirectoryThemeBridge() }
    val configLocal = remember(theme) { runCatching { theme.configLocal }.getOrNull() } ?: return fallback
    val colorsLocal = remember(theme) { runCatching { theme.colorsLocal }.getOrNull() } ?: return fallback
    val composer = currentComposer
    return runCatching {
        val config = composer.consume(configLocal)
        val colors = composer.consume(colorsLocal)
        val dark = theme.isDark(config)
        val surface = theme.background(colors, dark)
        fallback.copy(backdrop = surface, backdropDeep = surface,
            onBackdrop = if (dark) Color(0xFFF4F4F4) else Color(0xFF171717))
    }.getOrDefault(fallback)
}
