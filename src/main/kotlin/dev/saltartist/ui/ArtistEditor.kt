package dev.saltartist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saltartist.artwork.ArtworkPalette
import dev.saltartist.data.ArtistPageModel
import dev.saltartist.data.ArtistTrack
import dev.saltartist.settings.ArtistProfileStore
import java.io.File

/**
 * 「编辑艺人」面板：本地覆盖显示名、头图、歌曲显隐与歌曲/专辑名。
 *
 * 曲库是只读的，所以这里改的是插件自己的渲染层（见 [ArtistProfileStore]）。
 * 面板挂在页面最上层，点遮罩关闭。
 */
@Composable
fun ArtistEditor(
    model: ArtistPageModel,
    palette: ArtworkPalette,
    profiles: ArtistProfileStore,
    library: List<ArtistTrack> = emptyList(),
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val panelWidth = 640.dp
    // model.name is always the original route identity, never the display-name override.
    val profileRevision = profiles.revision.value
    val editorSnapshot = remember(profileRevision, model, library) { profiles.editorSnapshot(model, library) }
    var nameText by remember(model.name) { mutableStateOf(profiles.displayName(model.name) ?: model.name) }
    var addQuery by remember(model.name) { mutableStateOf("") }
    val hiddenIds = remember(profileRevision, model.name, editorSnapshot) {
        editorSnapshot.tracks.filter { profiles.isHidden(model.name, it.id) }.map { it.id }.toSet()
    }

    Box(
        Modifier
            .fillMaxSize()
            // 宿主底部还有一条迷你播放条（画在我们之上），把面板整体抬起来避让。
            .padding(bottom = 76.dp)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(panelWidth)
                .heightIn(min = 260.dp, max = 560.dp)
                .clip(shape)
                .background(palette.backdropDeep.copy(alpha = 0.97f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .clickable(enabled = false) { } // 吃掉点击，避免点面板内部就关掉
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SaltText("编辑艺人", size = 17.sp, weight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(10.dp))
                SaltText(model.name, size = 12.sp, color = Color.White.copy(alpha = 0.5f))
                Spacer(Modifier.weight(1f))
                SaltText("完成", size = 13.sp, color = palette.accent, modifier = Modifier.clickable { onClose() })
            }
            Spacer(Modifier.height(14.dp))

            SaltText("显示名", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(6.dp))
            EditorField(
                value = nameText,
                placeholder = model.name,
                onValueChange = { nameText = it },
                onCommit = { profiles.setName(model.name, nameText) },
                palette = palette,
            )
            Spacer(Modifier.height(14.dp))

            SaltText("头图", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    label = "选择图片…",
                    onClick = {
                        pickImageFile()?.let { profiles.setPhoto(model.name, it) }
                    },
                    accent = palette.accent,
                    filled = true,
                )
                GlassPill(
                    label = "用 Apple Music 头图",
                    onClick = { profiles.setPhoto(model.name, null) },
                    accent = palette.accent,
                    filled = false,
                )
            }
            profiles.photoPath(model.name)?.let { current ->
                Spacer(Modifier.height(6.dp))
                SaltText(
                    "当前：${current.name}",
                    size = 10.sp,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
            Spacer(Modifier.height(14.dp))

            SaltText("歌曲（取消勾选即隐藏）", size = 11.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 300.dp).fillMaxWidth()) {
                items(editorSnapshot.tracks, key = { it.id.ifEmpty { it.title } }) { track ->
                    val hidden = track.id in hiddenIds
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (hidden) Color.Transparent else palette.accent.copy(alpha = 0.85f))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                .clickable {
                                    if (track.id.isNotBlank()) profiles.toggleHidden(model.name, track.id)
                                },
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            EditorField(
                                value = profiles.trackTitle(model.name, track),
                                placeholder = track.title,
                                onValueChange = { },
                                onCommit = { profiles.setTrackTitle(model.name, track.id, it) },
                                palette = palette,
                                compact = true,
                            )
                        }
                        if (track.id.isNotBlank() && profiles.isAdded(model.name, track.id)) {
                            Spacer(Modifier.width(8.dp))
                            SaltText(
                                "移除",
                                size = 11.sp,
                                color = palette.accent,
                                modifier = Modifier.clickable { profiles.removeTrack(model.name, track.id) },
                            )
                        }
                    }
                }
                items(editorSnapshot.albums, key = { "album:${it.title}|${it.artist}" }) { album ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(26.dp))
                        SaltText("专辑", size = 10.sp, color = Color.White.copy(alpha = 0.45f))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            EditorField(
                                value = profiles.albumTitle(model.name, album.title),
                                placeholder = album.title,
                                onValueChange = { },
                                onCommit = { profiles.setAlbumTitle(model.name, album.title, it) },
                                palette = palette,
                                compact = true,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 添加歌曲：曲库里搜别的艺人的曲子，加到这个页面上。
            // Hidden originals already have a checkbox above; do not add them as extra duplicates.
            val pageIds = remember(editorSnapshot) {
                editorSnapshot.tracks.map { it.id }.toSet()
            }
            val candidates = remember(addQuery, library, pageIds) {
                val q = addQuery.trim()
                if (q.isEmpty()) {
                    emptyList()
                } else {
                    library.asSequence()
                        .filter { it.id.isNotBlank() && it.id !in pageIds }
                        .filter {
                            it.title.contains(q, ignoreCase = true) ||
                                it.artist.contains(q, ignoreCase = true) ||
                                it.album.contains(q, ignoreCase = true)
                        }
                        .take(24)
                        .toList()
                }
            }
            SaltText(
                "添加歌曲（搜索整库，回车无关，输入即过滤）",
                size = 11.sp,
                color = Color.White.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(6.dp))
            EditorField(
                value = addQuery,
                placeholder = "搜歌名 / 歌手 / 专辑…",
                onValueChange = { addQuery = it },
                onCommit = { },
                palette = palette,
                compact = true,
            )
            if (candidates.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 150.dp).fillMaxWidth()) {
                    items(candidates, key = { "add:${it.id}" }) { track ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                SaltText(
                                    track.title,
                                    size = 12.sp,
                                    color = Color.White.copy(alpha = 0.92f),
                                    maxLines = 1,
                                )
                                SaltText(
                                    listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                                    size = 10.sp,
                                    color = Color.White.copy(alpha = 0.45f),
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            GlassPill(
                                label = "添加",
                                onClick = { profiles.addTrack(model.name, track.id) },
                                accent = palette.accent,
                                filled = false,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassPill(
                    label = "恢复默认",
                    onClick = { profiles.reset(model.name); onClose() },
                    accent = palette.accent,
                    filled = false,
                )
                Spacer(Modifier.weight(1f))
                SaltText(
                    "只影响本插件的显示，不改曲库",
                    size = 10.sp,
                    color = Color.White.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun EditorField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    palette: ArtworkPalette,
    compact: Boolean = false,
) {
    var text by remember(value) { mutableStateOf(value) }
    // 打字时只更新本地状态，停手 500ms 才写回覆盖文件——避免每敲一个字符就落盘。
    androidx.compose.runtime.LaunchedEffect(text) {
        kotlinx.coroutines.delay(500)
        if (text != value) onCommit(text)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 9.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(it)
            },
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White.copy(alpha = 0.92f),
                fontSize = if (compact) 12.sp else 13.sp,
            ),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.fillMaxWidth(),
        )
        if (text.isEmpty()) {
            SaltText(placeholder, size = if (compact) 12.sp else 13.sp, color = Color.White.copy(alpha = 0.35f))
        }
    }
}

/** AWT 原生选择框；Compose Desktop 里没有内置的文件选择器。 */
internal fun pickImageFile(title: String = "选择艺人写真"): File? = runCatching {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val lower = name.lowercase()
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp")
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return@runCatching null
    val name = dialog.file ?: return@runCatching null
    File(dir, name).takeIf { it.isFile }
}.getOrNull()
