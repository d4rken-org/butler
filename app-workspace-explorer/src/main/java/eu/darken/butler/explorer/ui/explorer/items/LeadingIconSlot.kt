package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Bookmark
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.ui.common.AppAttributionBadge
import eu.darken.butler.workspace.ui.common.CornerBadgeBox
import eu.darken.butler.workspace.ui.common.GlyphBadge

/**
 * Slot wrapper for a row/grid item's leading icon area, overlaying its [ItemDecorations].
 *
 * Corners: favorite at BottomEnd, app at BottomStart. The app badge only shows where the caller
 * passes [appBadgeSize].
 */
@Composable
fun LeadingIconSlot(
    modifier: Modifier = Modifier,
    decorations: ItemDecorations = ItemDecorations(),
    badgeSize: Dp = 14.dp,
    appBadgeSize: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val appAttribution = decorations.appAttribution
    CornerBadgeBox(
        modifier = modifier,
        bottomStart = if (appAttribution != null && appBadgeSize != null) {
            { AppAttributionBadge(attribution = appAttribution, size = appBadgeSize) }
        } else {
            null
        },
        bottomEnd = if (decorations.isFavorite) {
            { GlyphBadge(icon = Icons.TwoTone.Bookmark, size = badgeSize) }
        } else {
            null
        },
        content = content,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LeadingIconSlotRowFavoritedPreview() {
    LeadingIconSlot(
        modifier = Modifier.size(32.dp),
        decorations = ItemDecorations(isFavorite = true),
    ) {
        Icon(
            imageVector = Icons.TwoTone.Folder,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LeadingIconSlotRowPlainPreview() {
    LeadingIconSlot(modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = Icons.TwoTone.Folder,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LeadingIconSlotGridFavoritedPreview() {
    LeadingIconSlot(
        modifier = Modifier.size(20.dp),
        decorations = ItemDecorations(isFavorite = true),
        badgeSize = 10.dp,
    ) {
        Icon(
            imageVector = Icons.TwoTone.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}
