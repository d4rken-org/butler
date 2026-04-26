package eu.darken.butler.explorer.ui.explorer.items

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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2

/**
 * Slot wrapper for a row/grid item's leading icon area. Single source of truth for
 * decoration overlays (currently the favorite bookmark badge; future: sync-pending dot,
 * error overlay, etc.).
 *
 * Both [eu.darken.butler.explorer.ui.explorer.items.row.FileRowBase] and
 * [eu.darken.butler.explorer.ui.explorer.items.grid.FileGridBase] delegate to this
 * composable. Adding a new uniformly-applied decoration is a single-file change here +
 * a field in [ItemDecorations] + a derivation in
 * [eu.darken.butler.explorer.ui.explorer.items.ExplorerItemRenderer.decorationsFor].
 *
 * Convention for future decorations: pick distinct alignment slots (BottomEnd, TopEnd,
 * TopStart, BottomStart) so badges don't collide. Keep at most one decoration per slot.
 *
 * The outer [Box] preserves [Alignment.Center] so leaf content that doesn't fill the
 * container (e.g., a 16dp icon inside a 32dp slot) stays centered. Per-decoration
 * [Modifier.align] overrides for badge children.
 */
@Composable
fun LeadingIconSlot(
    modifier: Modifier = Modifier,
    decorations: ItemDecorations = ItemDecorations(),
    badgeSize: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        content()
        if (decorations.isFavorite) {
            Icon(
                imageVector = Icons.TwoTone.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
        // future decoration branches go here — single file change.
    }
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
