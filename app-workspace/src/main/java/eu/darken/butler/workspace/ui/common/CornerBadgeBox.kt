package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Bookmark
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2

/**
 * Overlays up to one badge per corner on [content]. Placement only: each badge sizes and styles
 * itself, and the caller decides which corner carries what.
 *
 * [content] stays centered, so a glyph smaller than the box keeps its position.
 */
@Composable
fun CornerBadgeBox(
    modifier: Modifier = Modifier,
    topStart: (@Composable () -> Unit)? = null,
    topEnd: (@Composable () -> Unit)? = null,
    bottomStart: (@Composable () -> Unit)? = null,
    bottomEnd: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        content()
        topStart?.let { Box(Modifier.align(Alignment.TopStart)) { it() } }
        topEnd?.let { Box(Modifier.align(Alignment.TopEnd)) { it() } }
        bottomStart?.let { Box(Modifier.align(Alignment.BottomStart)) { it() } }
        bottomEnd?.let { Box(Modifier.align(Alignment.BottomEnd)) { it() } }
    }
}

/** A small glyph badge, set off from the content by a surface disc. */
@Composable
fun GlyphBadge(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    size: Dp,
    tint: Color = MaterialTheme.colorScheme.tertiary,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CornerBadgeBoxPreview() {
    CornerBadgeBox(
        modifier = Modifier.size(48.dp),
        bottomStart = { GhostBadge(size = 22.dp) },
        bottomEnd = { GlyphBadge(icon = Icons.TwoTone.Bookmark, size = 18.dp) },
    ) {
        Icon(imageVector = Icons.TwoTone.Folder, contentDescription = null, modifier = Modifier.size(48.dp))
    }
}
