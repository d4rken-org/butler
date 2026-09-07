package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.DensityLarge
import androidx.compose.material.icons.twotone.DensityMedium
import androidx.compose.material.icons.twotone.DensitySmall
import androidx.compose.material.icons.twotone.GridView
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

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
        // The subtitle describes the title, so it sits tight against it; the gap below is a
        // group boundary and gets the wider one. A single arrangement spacing made all four
        // gaps equal and left the subtitle floating between heading and controls.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.searcher_action_view),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.searcher_view_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            SearcherViewStyle.Mode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = currentViewStyle.mode == mode,
                    onClick = { onApplyToTab(currentViewStyle.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SearcherViewStyle.Mode.entries.size,
                    ),
                    icon = {},
                ) {
                    StackedSegmentLabel(icon = modeIcon(mode), label = stringResource(modeLabel(mode)))
                }
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SearcherViewStyle.Density.entries.forEachIndexed { index, density ->
                SegmentedButton(
                    selected = currentViewStyle.density == density,
                    onClick = { onApplyToTab(currentViewStyle.copy(density = density)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SearcherViewStyle.Density.entries.size,
                    ),
                    icon = {},
                ) {
                    StackedSegmentLabel(
                        icon = densityIcon(density),
                        label = stringResource(densityLabel(density)),
                    )
                }
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

/**
 * Icon over label, so three segments fit a phone's width.
 *
 * The segmented button's own leading checkmark is switched off (`icon = {}`): the selected
 * container colour already says which one is picked, and the checkmark's slide-in animation
 * reflows the label every time the choice changes.
 */
@Composable
private fun StackedSegmentLabel(icon: ImageVector, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
