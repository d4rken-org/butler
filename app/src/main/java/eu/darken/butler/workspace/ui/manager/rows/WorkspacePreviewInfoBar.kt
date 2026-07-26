package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

const val TEST_TAG_WORKSPACE_CARD_INFOBAR = "workspace_card_infobar"

/**
 * Overlays the bottom edge of a workspace preview with the workspace's automatic identity.
 *
 * It sits on top of the preview instead of adding to the card, so neither its presence nor its
 * line count can change the card's height. The background is near-opaque rather than a gradient
 * scrim because the preview behind it is a screenshot of arbitrary content.
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
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (primaryText != null) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }

        if (secondaryText != null) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarPreview() {
    WorkspacePreviewInfoBar(
        primary = "Trash".toCaString(),
        secondary = "Recover deleted files".toCaString(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarPrimaryOnlyPreview() {
    WorkspacePreviewInfoBar(
        primary = "/storage/emulated/0/Download".toCaString(),
        secondary = null,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarSecondaryOnlyPreview() {
    WorkspacePreviewInfoBar(
        primary = null,
        secondary = "Manage installed applications".toCaString(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarLongTextPreview() {
    WorkspacePreviewInfoBar(
        primary = "*-2024-11-02-integration-test-run-logcat.txt".toCaString(),
        secondary = "/storage/emulated/0/DCIM/Camera, /storage/emulated/0/Documents, Downloads +2".toCaString(),
    )
}
