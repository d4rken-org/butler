package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.theming.onScrim

const val TEST_TAG_WORKSPACE_CARD_INFOBAR = "workspace_card_infobar"

/**
 * Overlays the bottom edge of a workspace preview with the workspace's automatic identity.
 *
 * It sits on top of the preview instead of adding to the card, so neither its presence nor its
 * line count can change the card's height. It follows the same scrim convention as the
 * Explorer/Apps/Saver grid label bars: a 60% scrim with [onScrim] text. The one deliberate
 * deviation is that both lines use labelSmall instead of labelMedium/labelSmall, because this bar
 * overlays a much smaller preview than those grid tiles.
 */
@Composable
fun WorkspacePreviewInfoBar(
    modifier: Modifier = Modifier,
    primary: CaString?,
    secondary: CaString?,
) {
    val primaryText = primary?.asComposable()?.takeIf { it.isNotBlank() }
    val secondaryText = secondary?.asComposable()?.takeIf { it.isNotBlank() }
    if (primaryText == null && secondaryText == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TEST_TAG_WORKSPACE_CARD_INFOBAR)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (primaryText != null) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onScrim,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }

        if (secondaryText != null) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onScrim.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
private fun ThumbnailStandIn(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(120.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Box(modifier = Modifier.align(Alignment.BottomStart)) {
            content()
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarPreview() = ThumbnailStandIn {
    WorkspacePreviewInfoBar(
        primary = "Trash".toCaString(),
        secondary = "Recover deleted files".toCaString(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarPrimaryOnlyPreview() = ThumbnailStandIn {
    WorkspacePreviewInfoBar(
        primary = "/storage/emulated/0/Download".toCaString(),
        secondary = null,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarSecondaryOnlyPreview() = ThumbnailStandIn {
    WorkspacePreviewInfoBar(
        primary = null,
        secondary = "Manage installed applications".toCaString(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarLongTextPreview() = ThumbnailStandIn {
    WorkspacePreviewInfoBar(
        primary = "*-2024-11-02-integration-test-run-logcat.txt".toCaString(),
        secondary = "/storage/emulated/0/DCIM/Camera, /storage/emulated/0/Documents, Downloads +2".toCaString(),
    )
}
