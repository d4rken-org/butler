package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.DensityLarge
import androidx.compose.material.icons.twotone.DensityMedium
import androidx.compose.material.icons.twotone.DensitySmall
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.apps.R
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.bottomsheet.ViewStyleOptionGroup
import eu.darken.butler.workspace.ui.bottomsheet.ViewStyleOptionRow

/**
 * Layout and density of the app list.
 *
 * Stateless on purpose: every control renders from [currentViewStyle] and emits a full style built
 * from it, so the controls follow the tab's live value and no tap can send back a value the sheet
 * has been holding since it opened.
 */
@Composable
fun ViewStyleOptionsSheet(
    modifier: Modifier = Modifier,
    currentViewStyle: AppsViewStyle,
    onApplyToTab: (AppsViewStyle) -> Unit,
    onSetAsDefault: (AppsViewStyle) -> Unit,
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
        // The list behind this sheet is the preview of what the controls do.
        scrimAlpha = 0.15f,
    ) {
        ViewStyleOptionsContent(
            currentViewStyle = currentViewStyle,
            onApplyToTab = onApplyToTab,
            onSetAsDefault = onSetAsDefault,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ViewStyleOptionsContent(
    modifier: Modifier = Modifier,
    currentViewStyle: AppsViewStyle,
    onApplyToTab: (AppsViewStyle) -> Unit,
    onSetAsDefault: (AppsViewStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.apps_action_view),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.apps_view_sheet_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Two options fit side by side; the three densities below do not, once each carries an
        // icon, a word like "Comfortable" and a checkmark.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppsViewStyle.Mode.entries.forEachIndexed { index, mode ->
                val selected = currentViewStyle.mode == mode
                SegmentedButton(
                    selected = selected,
                    onClick = { onApplyToTab(currentViewStyle.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppsViewStyle.Mode.entries.size,
                    ),
                    icon = { SegmentedButtonIcon(modeIcon(mode)) },
                ) {
                    SegmentedButtonLabel(label = stringResource(modeLabel(mode)), selected = selected)
                }
            }
        }

        HorizontalDivider()

        ViewStyleOptionGroup {
            AppsViewStyle.Density.entries.forEach { density ->
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
            TextButton(
                onClick = {
                    onSetAsDefault(currentViewStyle)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.apps_view_set_default_action))
            }
        }
    }
}

/** Replaces the segmented button's default leading checkmark, which moves behind the label. */
@Composable
private fun SegmentedButtonIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
    )
}

@Composable
private fun SegmentedButtonLabel(label: String, selected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label)
        if (selected) {
            Icon(
                imageVector = Icons.TwoTone.Check,
                contentDescription = null,
                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
            )
        }
    }
}

private fun modeLabel(mode: AppsViewStyle.Mode): Int = when (mode) {
    AppsViewStyle.Mode.LIST -> R.string.apps_view_mode_list_label
    AppsViewStyle.Mode.GRID -> R.string.apps_view_mode_grid_label
}

private fun modeIcon(mode: AppsViewStyle.Mode): ImageVector = when (mode) {
    AppsViewStyle.Mode.LIST -> Icons.AutoMirrored.TwoTone.ViewList
    AppsViewStyle.Mode.GRID -> Icons.TwoTone.GridView
}

private fun densityLabel(density: AppsViewStyle.Density): Int = when (density) {
    AppsViewStyle.Density.COMPACT -> R.string.apps_view_density_compact_label
    AppsViewStyle.Density.COMFORTABLE -> R.string.apps_view_density_comfortable_label
    AppsViewStyle.Density.DETAILED -> R.string.apps_view_density_detailed_label
}

/** Tighter rows to looser ones, so the icons read as the same scale the labels describe. */
private fun densityIcon(density: AppsViewStyle.Density): ImageVector = when (density) {
    AppsViewStyle.Density.COMPACT -> Icons.TwoTone.DensitySmall
    AppsViewStyle.Density.COMFORTABLE -> Icons.TwoTone.DensityMedium
    AppsViewStyle.Density.DETAILED -> Icons.TwoTone.DensityLarge
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewStyleOptionsSheetListPreview() {
    PreviewWrapper {
        ViewStyleOptionsContent(
            currentViewStyle = AppsViewStyle(),
            onApplyToTab = {},
            onSetAsDefault = {},
            onDismiss = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewStyleOptionsSheetGridDetailedPreview() {
    PreviewWrapper {
        ViewStyleOptionsContent(
            currentViewStyle = AppsViewStyle(
                mode = AppsViewStyle.Mode.GRID,
                density = AppsViewStyle.Density.DETAILED,
            ),
            onApplyToTab = {},
            onSetAsDefault = {},
            onDismiss = {},
        )
    }
}
