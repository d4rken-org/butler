package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.FilterComparator
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.searcher.ui.search.elements.DateFilterPreset
import eu.darken.butler.searcher.ui.search.elements.findPresetForInstant
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Bottom sheet for editing a single date condition with comparator selection.
 */
@Composable
fun DateConditionEditSheet(
    modifier: Modifier = Modifier,
    visible: Boolean,
    existingCondition: FilterCondition.ModifiedDate?,
    onDismiss: () -> Unit,
    onApply: (FilterCondition.ModifiedDate) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = visible,
        onDismiss = onDismiss,
        bottomInset = bottomInset,
    ) {
        DateConditionEditContent(
            existingCondition = existingCondition,
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

/**
 * Direction for date conditions - simplified from full comparators.
 * For dates, "After" means files modified more recently than X,
 * and "Before" means files modified earlier than X.
 */
private enum class DateDirection(val labelResId: Int) {
    AFTER(R.string.searcher_filter_date_direction_after),
    BEFORE(R.string.searcher_filter_date_direction_before),
}

@Composable
private fun DateConditionEditContent(
    existingCondition: FilterCondition.ModifiedDate?,
    onDismiss: () -> Unit,
    onApply: (FilterCondition.ModifiedDate) -> Unit,
) {
    // Determine initial direction from existing condition's comparator
    val initialDirection = when (existingCondition?.comparator) {
        FilterComparator.GT, FilterComparator.GTE -> DateDirection.AFTER
        FilterComparator.LT, FilterComparator.LTE -> DateDirection.BEFORE
        FilterComparator.EQ -> DateDirection.AFTER // Default for equality
        null -> DateDirection.AFTER
    }

    var selectedDirection by rememberSaveable(existingCondition) {
        mutableStateOf(initialDirection)
    }
    var selectedPreset by rememberSaveable(existingCondition) {
        val preset = findPresetForInstant(existingCondition?.instant)
        // Default to Last 7 days if no existing condition (ANY is not useful for conditions)
        mutableStateOf(if (preset == DateFilterPreset.ANY) DateFilterPreset.LAST_7_DAYS else preset)
    }

    var presetExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.searcher_filter_date_section),
                style = MaterialTheme.typography.titleLarge,
            )

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.TwoTone.Close,
                    contentDescription = stringResource(eu.darken.butler.common.R.string.general_close_action),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Direction segmented buttons (After/Before)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                DateDirection.entries.forEachIndexed { index, direction ->
                    SegmentedButton(
                        selected = selectedDirection == direction,
                        onClick = { selectedDirection = direction },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = DateDirection.entries.size,
                        ),
                    ) {
                        Text(stringResource(direction.labelResId))
                    }
                }
            }

            // Date preset dropdown (full width)
            ExposedDropdownMenuBox(
                expanded = presetExpanded,
                onExpandedChange = { presetExpanded = it },
            ) {
                OutlinedTextField(
                    value = stringResource(selectedPreset.labelResId),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.searcher_filter_date_value_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = presetExpanded,
                    onDismissRequest = { presetExpanded = false },
                ) {
                    // Only show non-ANY presets for condition editing
                    DateFilterPreset.entries.filter { it != DateFilterPreset.ANY }.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(preset.labelResId)) },
                            onClick = {
                                selectedPreset = preset
                                presetExpanded = false
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Footer buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }

            Button(
                onClick = {
                    val duration = selectedPreset.duration
                    if (duration != null) {
                        val now = Clock.System.now()
                        val instant = now - duration
                        val comparator = when (selectedDirection) {
                            DateDirection.AFTER -> FilterComparator.GT
                            DateDirection.BEFORE -> FilterComparator.LT
                        }
                        onApply(
                            FilterCondition.ModifiedDate(
                                comparator = comparator,
                                instant = instant,
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = selectedPreset != DateFilterPreset.ANY,
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_apply_action))
            }
        }
    }
}

@Preview2
@Composable
private fun DateConditionEditSheetNewPreview() {
    PreviewWrapper {
        DateConditionEditSheet(
            visible = true,
            existingCondition = null,
            onDismiss = {},
            onApply = {},
        )
    }
}

@Preview2
@Composable
private fun DateConditionEditSheetEditingPreview() {
    PreviewWrapper {
        DateConditionEditSheet(
            visible = true,
            existingCondition = FilterCondition.ModifiedDate(
                comparator = FilterComparator.GT,
                instant = Clock.System.now() - 7.days,
            ),
            onDismiss = {},
            onApply = {},
        )
    }
}
