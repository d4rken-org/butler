package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.DensityLarge
import androidx.compose.material.icons.twotone.DensityMedium
import androidx.compose.material.icons.twotone.DensitySmall
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.bottomsheet.ViewStyleOptionGroup
import eu.darken.butler.workspace.ui.bottomsheet.ViewStyleOptionRow

/**
 * Layout and density of the listing.
 *
 * Stateless on purpose: every control renders from [currentViewStyle] and emits a full style built
 * from it, so a change made elsewhere - "apply to all tabs" from another pane - moves these controls
 * too, and no tap can send a value the sheet has been holding since it opened.
 */
@Composable
fun ViewStyleOptionsSheet(
    modifier: Modifier = Modifier,
    currentViewStyle: ExplorerViewStyle,
    onApplyToTab: (ExplorerViewStyle) -> Unit,
    onApplyToAllTabs: (ExplorerViewStyle) -> Unit,
    onSetAsDefault: (ExplorerViewStyle) -> Unit,
    onDismiss: () -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        // The listing behind this sheet is the preview of what the controls do.
        scrimAlpha = 0.15f,
    ) {
        ViewStyleOptionsContent(
            currentViewStyle = currentViewStyle,
            onApplyToTab = onApplyToTab,
            onApplyToAllTabs = onApplyToAllTabs,
            onSetAsDefault = onSetAsDefault,
        )
    }
}

@Composable
private fun ViewStyleOptionsContent(
    modifier: Modifier = Modifier,
    currentViewStyle: ExplorerViewStyle,
    onApplyToTab: (ExplorerViewStyle) -> Unit,
    onApplyToAllTabs: (ExplorerViewStyle) -> Unit,
    onSetAsDefault: (ExplorerViewStyle) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.explorer_action_view),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.explorer_view_sheet_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ViewStyleOptionGroup {
            ExplorerViewStyle.Mode.entries.forEach { mode ->
                ViewStyleOptionRow(
                    icon = modeIcon(mode),
                    label = stringResource(modeLabel(mode)),
                    selected = currentViewStyle.mode == mode,
                    onClick = { onApplyToTab(currentViewStyle.copy(mode = mode)) },
                )
            }
        }

        HorizontalDivider()

        ViewStyleOptionGroup {
            ExplorerViewStyle.Density.entries.forEach { density ->
                ViewStyleOptionRow(
                    icon = densityIcon(density),
                    label = stringResource(densityLabel(density)),
                    selected = currentViewStyle.density == density,
                    onClick = { onApplyToTab(currentViewStyle.copy(density = density)) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onApplyToAllTabs(currentViewStyle) }) {
                Text(stringResource(R.string.explorer_view_apply_all_tabs_action))
            }
            TextButton(onClick = { onSetAsDefault(currentViewStyle) }) {
                Text(stringResource(R.string.explorer_view_set_default_action))
            }
        }
    }
}

private fun modeLabel(mode: ExplorerViewStyle.Mode): Int = when (mode) {
    ExplorerViewStyle.Mode.LIST -> R.string.explorer_view_mode_list_label
    ExplorerViewStyle.Mode.GRID -> R.string.explorer_view_mode_grid_label
}

private fun modeIcon(mode: ExplorerViewStyle.Mode): ImageVector = when (mode) {
    ExplorerViewStyle.Mode.LIST -> Icons.AutoMirrored.TwoTone.ViewList
    ExplorerViewStyle.Mode.GRID -> Icons.TwoTone.GridView
}

private fun densityLabel(density: ExplorerViewStyle.Density): Int = when (density) {
    ExplorerViewStyle.Density.COMPACT -> R.string.explorer_view_density_compact_label
    ExplorerViewStyle.Density.COMFORTABLE -> R.string.explorer_view_density_comfortable_label
    ExplorerViewStyle.Density.DETAILED -> R.string.explorer_view_density_detailed_label
}

/** Tighter rows to looser ones, so the icons read as the same scale the labels describe. */
private fun densityIcon(density: ExplorerViewStyle.Density): ImageVector = when (density) {
    ExplorerViewStyle.Density.COMPACT -> Icons.TwoTone.DensitySmall
    ExplorerViewStyle.Density.COMFORTABLE -> Icons.TwoTone.DensityMedium
    ExplorerViewStyle.Density.DETAILED -> Icons.TwoTone.DensityLarge
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewStyleOptionsSheetListPreview() {
    PreviewWrapper {
        ViewStyleOptionsContent(
            currentViewStyle = ExplorerViewStyle(),
            onApplyToTab = {},
            onApplyToAllTabs = {},
            onSetAsDefault = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewStyleOptionsSheetGridDetailedPreview() {
    PreviewWrapper {
        ViewStyleOptionsContent(
            currentViewStyle = ExplorerViewStyle(
                mode = ExplorerViewStyle.Mode.GRID,
                density = ExplorerViewStyle.Density.DETAILED,
            ),
            onApplyToTab = {},
            onApplyToAllTabs = {},
            onSetAsDefault = {},
        )
    }
}
