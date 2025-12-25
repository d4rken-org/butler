package eu.darken.butler.searcher.ui.search.elements

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.FilterComparator
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.searcher.core.SearchFilter
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Displays active filter conditions as chips with individual remove capability.
 * Each condition is displayed as one chip. Click chip to edit that condition,
 * click × to remove it. Includes an "Add filter" button to add new conditions.
 */
@Composable
fun FilterChipBar(
    modifier: Modifier = Modifier,
    filter: SearchFilter,
    onConditionClick: (FilterCondition) -> Unit,
    onAddSizeCondition: () -> Unit,
    onAddDateCondition: () -> Unit,
    onAddTypeCondition: () -> Unit,
    onRemoveCondition: (FilterCondition) -> Unit,
) {
    val context = LocalContext.current
    var showAddMenu by remember { mutableStateOf(false) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Display each condition as its own chip
        filter.conditions.forEach { condition ->
            val label = formatConditionLabel(condition, context)
            CompactFilterChip(
                label = label,
                onClick = { onConditionClick(condition) },
                onRemove = { onRemoveCondition(condition) },
            )
        }

        // Add filter button with dropdown
        Box {
            CompactAssistChip(
                label = stringResource(R.string.searcher_filter_add_action),
                leadingIcon = Icons.TwoTone.Add,
                onClick = { showAddMenu = true },
            )

            DropdownMenu(
                expanded = showAddMenu,
                onDismissRequest = { showAddMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.searcher_filter_size_section)) },
                    onClick = {
                        showAddMenu = false
                        onAddSizeCondition()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.searcher_filter_date_section)) },
                    onClick = {
                        showAddMenu = false
                        onAddDateCondition()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.searcher_filter_type_label)) },
                    onClick = {
                        showAddMenu = false
                        onAddTypeCondition()
                    },
                )
            }
        }
    }
}

@Composable
private fun formatConditionLabel(condition: FilterCondition, context: android.content.Context): String {
    return when (condition) {
        is FilterCondition.Size -> {
            val symbol = condition.comparator.symbol
            val sizeStr = Formatter.formatShortFileSize(context, condition.bytes)
            "$symbol$sizeStr"
        }
        is FilterCondition.ModifiedDate -> {
            val direction = when (condition.comparator) {
                FilterComparator.GT, FilterComparator.GTE -> stringResource(R.string.searcher_filter_date_direction_after)
                FilterComparator.LT, FilterComparator.LTE -> stringResource(R.string.searcher_filter_date_direction_before)
                FilterComparator.EQ -> stringResource(R.string.searcher_filter_date_direction_after)
            }
            val preset = findPresetForInstant(condition.instant)
            val dateStr = if (preset != DateFilterPreset.ANY) {
                stringResource(preset.labelResId)
            } else {
                // Fallback for custom dates
                stringResource(R.string.searcher_filter_date_value_label)
            }
            "$direction $dateStr"
        }
        is FilterCondition.Type -> when (condition.fileType) {
            FileType.FILE -> stringResource(R.string.searcher_filter_type_files)
            FileType.DIRECTORY -> stringResource(R.string.searcher_filter_type_directories)
            else -> condition.fileType.name
        }
    }
}

fun findPresetForInstant(instant: Instant?): DateFilterPreset {
    if (instant == null) return DateFilterPreset.ANY

    val now = Clock.System.now()
    val durationFromNow = now - instant

    return DateFilterPreset.entries
        .filter { it.duration != null }
        .minByOrNull { preset ->
            kotlin.math.abs((preset.duration!! - durationFromNow).inWholeMinutes)
        }
        ?.takeIf { preset ->
            // Only match if within 1 hour tolerance
            kotlin.math.abs((preset.duration!! - durationFromNow).inWholeMinutes) < 60
        }
        ?: DateFilterPreset.ANY
}

enum class DateFilterPreset(
    val labelResId: Int,
    val duration: Duration?,
) {
    ANY(R.string.searcher_filter_date_any, null),
    LAST_24_HOURS(R.string.searcher_filter_date_24h, 24.hours),
    LAST_7_DAYS(R.string.searcher_filter_date_7d, 7.days),
    LAST_30_DAYS(R.string.searcher_filter_date_30d, 30.days),
    LAST_90_DAYS(R.string.searcher_filter_date_90d, 90.days),
    LAST_YEAR(R.string.searcher_filter_date_1y, 365.days),
}


@Preview2
@Composable
private fun FilterChipBarEmptyPreview() {
    PreviewWrapper {
        FilterChipBar(
            filter = SearchFilter(),
            onConditionClick = {},
            onAddSizeCondition = {},
            onAddDateCondition = {},
            onAddTypeCondition = {},
            onRemoveCondition = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun FilterChipBarSingleConditionPreview() {
    PreviewWrapper {
        FilterChipBar(
            filter = SearchFilter(
                conditions = listOf(
                    FilterCondition.Size(FilterComparator.GTE, 100L * 1024 * 1024),
                ),
            ),
            onConditionClick = {},
            onAddSizeCondition = {},
            onAddDateCondition = {},
            onAddTypeCondition = {},
            onRemoveCondition = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun FilterChipBarMultipleConditionsPreview() {
    PreviewWrapper {
        FilterChipBar(
            filter = SearchFilter(
                conditions = listOf(
                    FilterCondition.Size(FilterComparator.GTE, 100L * 1024 * 1024),
                    FilterCondition.Size(FilterComparator.LTE, 500L * 1024 * 1024),
                    FilterCondition.ModifiedDate(FilterComparator.GT, Clock.System.now() - 7.days),
                ),
            ),
            onConditionClick = {},
            onAddSizeCondition = {},
            onAddDateCondition = {},
            onAddTypeCondition = {},
            onRemoveCondition = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun FilterChipBarAllTypesPreview() {
    PreviewWrapper {
        FilterChipBar(
            filter = SearchFilter(
                conditions = listOf(
                    FilterCondition.Size(FilterComparator.GT, 50L * 1024 * 1024),
                    FilterCondition.Size(FilterComparator.LT, 1024L * 1024 * 1024),
                    FilterCondition.ModifiedDate(FilterComparator.GT, Clock.System.now() - 30.days),
                    FilterCondition.Type(FileType.FILE),
                ),
            ),
            onConditionClick = {},
            onAddSizeCondition = {},
            onAddDateCondition = {},
            onAddTypeCondition = {},
            onRemoveCondition = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
