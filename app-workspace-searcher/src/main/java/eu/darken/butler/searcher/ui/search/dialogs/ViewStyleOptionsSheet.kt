package eu.darken.butler.searcher.ui.search.dialogs

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
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.bottomsheet.ViewStyleOptionGroup
import eu.darken.butler.workspace.ui.bottomsheet.ViewStyleOptionRow

/**
 * Layout and density of the results.
 *
 * Stateless on purpose: every control renders from [currentViewStyle] and emits a full style built
 * from it, so the controls follow the tab's live value and no tap can send back a value the sheet
 * has been holding since it opened.
 */
@Composable
fun ViewStyleOptionsSheet(
    modifier: Modifier = Modifier,
    visible: Boolean,
    currentViewStyle: SearcherViewStyle,
    onApplyToTab: (SearcherViewStyle) -> Unit,
    onSetAsDefault: (SearcherViewStyle) -> Unit,
    onDismiss: () -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = visible,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        // The results behind this sheet are the preview of what the controls do.
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
    currentViewStyle: SearcherViewStyle,
    onApplyToTab: (SearcherViewStyle) -> Unit,
    onSetAsDefault: (SearcherViewStyle) -> Unit,
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
            text = stringResource(R.string.searcher_action_view),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.searcher_view_sheet_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Two options fit side by side; the three densities below do not, once each carries an
        // icon, a word like "Comfortable" and a checkmark.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SearcherViewStyle.Mode.entries.forEachIndexed { index, mode ->
                val selected = currentViewStyle.mode == mode
                SegmentedButton(
                    selected = selected,
                    onClick = { onApplyToTab(currentViewStyle.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SearcherViewStyle.Mode.entries.size,
                    ),
                    icon = { SegmentedButtonIcon(modeIcon(mode)) },
                ) {
                    SegmentedButtonLabel(label = stringResource(modeLabel(mode)), selected = selected)
                }
            }
        }

        HorizontalDivider()

        ViewStyleOptionGroup {
            SearcherViewStyle.Density.entries.forEach { density ->
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
                Text(stringResource(R.string.searcher_view_set_default_action))
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

private fun modeLabel(mode: SearcherViewStyle.Mode): Int = when (mode) {
    SearcherViewStyle.Mode.LIST -> R.string.searcher_view_mode_list_label
    SearcherViewStyle.Mode.GRID -> R.string.searcher_view_mode_grid_label
}

private fun modeIcon(mode: SearcherViewStyle.Mode): ImageVector = when (mode) {
    SearcherViewStyle.Mode.LIST -> Icons.AutoMirrored.TwoTone.ViewList
    SearcherViewStyle.Mode.GRID -> Icons.TwoTone.GridView
}

private fun densityLabel(density: SearcherViewStyle.Density): Int = when (density) {
    SearcherViewStyle.Density.COMPACT -> R.string.searcher_view_density_compact_label
    SearcherViewStyle.Density.COMFORTABLE -> R.string.searcher_view_density_comfortable_label
    SearcherViewStyle.Density.DETAILED -> R.string.searcher_view_density_detailed_label
}

/** Tighter rows to looser ones, so the icons read as the same scale the labels describe. */
private fun densityIcon(density: SearcherViewStyle.Density): ImageVector = when (density) {
    SearcherViewStyle.Density.COMPACT -> Icons.TwoTone.DensitySmall
    SearcherViewStyle.Density.COMFORTABLE -> Icons.TwoTone.DensityMedium
    SearcherViewStyle.Density.DETAILED -> Icons.TwoTone.DensityLarge
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewStyleOptionsSheetListPreview() {
    PreviewWrapper {
        ViewStyleOptionsContent(
            currentViewStyle = SearcherViewStyle(),
            onApplyToTab = {},
            onSetAsDefault = {},
            onDismiss = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewStyleOptionsSheetGridCompactPreview() {
    PreviewWrapper {
        ViewStyleOptionsContent(
            currentViewStyle = SearcherViewStyle(
                mode = SearcherViewStyle.Mode.GRID,
                density = SearcherViewStyle.Density.COMPACT,
            ),
            onApplyToTab = {},
            onSetAsDefault = {},
            onDismiss = {},
        )
    }
}
