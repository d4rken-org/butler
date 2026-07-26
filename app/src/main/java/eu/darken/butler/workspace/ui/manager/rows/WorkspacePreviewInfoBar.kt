package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

private val INFOBAR_LINE_HEIGHT = 13.sp

/**
 * Overlays the bottom edge of a workspace preview with the workspace's automatic identity.
 *
 * It sits on top of the preview instead of adding to the card, so neither its presence nor its
 * line count can change the card's height. It follows the same scrim convention as the
 * Explorer/Apps/Saver grid label bars: a 60% scrim with [onScrim] text. The one deliberate
 * deviation is that both lines use labelSmall instead of labelMedium/labelSmall, because this bar
 * overlays a much smaller preview than those grid tiles.
 *
 * Whenever the bar is drawn it always reserves two rows, even if only one of them carries text, so
 * the scrim reads as one consistent band across the grid instead of a short bar next to a tall one.
 * The reserved height is derived from [INFOBAR_LINE_HEIGHT] and therefore follows the user's
 * font-size setting. A line without text stays empty, it is not drawn as blank text, and a bar with
 * nothing to say at all is not drawn.
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

    val lineDp = with(LocalDensity.current) { INFOBAR_LINE_HEIGHT.toDp() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TEST_TAG_WORKSPACE_CARD_INFOBAR)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .height(lineDp * 2),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (primaryText != null) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = INFOBAR_LINE_HEIGHT),
                color = MaterialTheme.colorScheme.onScrim,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        } else {
            Spacer(modifier = Modifier.height(lineDp))
        }

        if (secondaryText != null) {
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = INFOBAR_LINE_HEIGHT),
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

/** The second row stays as empty scrim, the text keeps the first row. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarPrimaryOnlyPreview() = ThumbnailStandIn {
    WorkspacePreviewInfoBar(
        primary = "/storage/emulated/0/Download".toCaString(),
        secondary = null,
    )
}

/** The first row stays as empty scrim, the text keeps the second row. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarSecondaryOnlyPreview() = ThumbnailStandIn {
    WorkspacePreviewInfoBar(
        primary = null,
        secondary = "Manage installed applications".toCaString(),
    )
}

/** Both bars must end up the same height, that is the point of the reserved row. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewInfoBarUniformHeightPreview() = Row {
    ThumbnailStandIn {
        WorkspacePreviewInfoBar(
            primary = "Downloads".toCaString(),
            secondary = null,
        )
    }
    ThumbnailStandIn {
        WorkspacePreviewInfoBar(
            primary = "Search results".toCaString(),
            secondary = "128 matches".toCaString(),
        )
    }
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
