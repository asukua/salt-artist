package dev.saltartist.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** At most two shelves are retained during a short, frame-clock-driven page slide. */
internal class CatalogPageMotion(initialPage: Int) {
    var page by mutableIntStateOf(initialPage)
        private set
    var previousPage by mutableStateOf<Int?>(null)
        private set
    private val animation = Animatable(1f)
    val fraction: Float get() = animation.value
    var direction by mutableIntStateOf(1)
        private set
    val isRunning: Boolean get() = previousPage != null
    private var generation = 0

    suspend fun showPage(target: Int) {
        if (target == page) return
        val request = ++generation
        val outgoing = page
        animation.snapTo(0f)
        previousPage = outgoing
        direction = if (target > outgoing) 1 else -1
        page = target
        try {
            animation.animateTo(1f, spring(stiffness = 500f, visibilityThreshold = 0.001f))
        } finally {
            if (request == generation) previousPage = null
        }
    }
}
