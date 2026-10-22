package dev.saltartist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.saltartist.bridge.HostReflection

/**
 * ArtistItem's native Row uses Arrangement.Start and an explicit Spacer after its
 * 48dp avatar. A zero-width sibling adds no gap and paints over exactly that slot.
 * Keeping the original avatar provides its real cover/placeholder until AM loads.
 */
@Composable
internal fun ArtistDirectoryAvatar(artist: Any?, deps: ArtistScreenDeps) {
    val name = HostReflection.stringProperty(artist, "getName").orEmpty()
    if (name.isBlank()) return
    val identity = deps.settings.read().identityPolicy
    val image = runCatching { deps.artwork.artistAvatar(name, identity) }.getOrElse {
        deps.log.logOnce("directory-avatar-fallback", "directory avatar kept native cover: ${deps.log.describe(it)}")
        null
    } ?: return
    Layout(
        content = {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        },
    ) { measurables, _ ->
        val diameter = 48.dp.roundToPx()
        val imagePlaceable = measurables.single().measure(Constraints.fixed(diameter, diameter))
        layout(width = 0, height = diameter) {
            imagePlaceable.placeRelative(x = -diameter, y = 0)
        }
    }
}
