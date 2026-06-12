package eu.darken.butler.searcher.ui.search.dialogs

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.ui.SizeParser
import eu.darken.butler.searcher.R
import eu.darken.butler.workspace.contracts.searcher.FilterComparator
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

/**
 * Direction for size conditions - simplified to common use cases.
 */
private enum class SizeDirection(val labelResId: Int, val comparator: FilterComparator) {
    AT_LEAST(R.string.searcher_filter_size_at_least, FilterComparator.GTE),
    AT_MOST(R.string.searcher_filter_size_at_most, FilterComparator.LTE),
}

/**
 * Bottom sheet for editing a single size condition with comparator selection.
 */
@Composable
fun SizeConditionEditSheet(
    modifier: Modifier = Modifier,
    visible: Boolean,
    existingCondition: FilterCondition.Size?,
    onDismiss: () -> Unit,
    onApply: (FilterCondition.Size) -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = visible,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        SizeConditionEditContent(
            existingCondition = existingCondition,
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

@Composable
private fun SizeConditionEditContent(
    existingCondition: FilterCondition.Size?,
    onDismiss: () -> Unit,
    onApply: (FilterCondition.Size) -> Unit,
) {
    val context = LocalContext.current
    val sizeParser = remember { SizeParser(context) }

    // Map existing comparator to direction (default to AT_LEAST)
    val initialDirection = when (existingCondition?.comparator) {
        FilterComparator.GTE, FilterComparator.GT -> SizeDirection.AT_LEAST
        FilterComparator.LTE, FilterComparator.LT -> SizeDirection.AT_MOST
        FilterComparator.EQ -> SizeDirection.AT_LEAST // Map equality to "at least"
        null -> SizeDirection.AT_LEAST
    }

    var selectedDirection by rememberSaveable(existingCondition) {
        mutableStateOf(initialDirection)
    }
    var sizeText by rememberSaveable(existingCondition) {
        mutableStateOf(
            existingCondition?.bytes?.let { Formatter.formatShortFileSize(context, it) } ?: ""
        )
    }
    var sizeError by remember { mutableStateOf(false) }

    fun parseSize(text: String): Long? {
        if (text.isBlank()) return null
        return sizeParser.parse(text)
    }

    fun validateSize() {
        sizeError = sizeText.isNotBlank() && parseSize(sizeText) == null
    }

    val parsedBytes = parseSize(sizeText)
    val isInputValid = !sizeError && sizeText.isNotBlank() && parsedBytes != null

    val handleApply = {
        if (isInputValid) {
            onApply(FilterCondition.Size(selectedDirection.comparator, parsedBytes!!))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        // Header
        Text(
            text = stringResource(R.string.searcher_filter_size_section),
            style = MaterialTheme.typography.titleLarge,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Direction segmented buttons (At least / At most)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SizeDirection.entries.forEachIndexed { index, direction ->
                    SegmentedButton(
                        selected = selectedDirection == direction,
                        onClick = { selectedDirection = direction },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = SizeDirection.entries.size,
                        ),
                    ) {
                        Text(stringResource(direction.labelResId))
                    }
                }
            }

            // Size input (full width)
            OutlinedTextField(
                value = sizeText,
                onValueChange = {
                    sizeText = it
                    validateSize()
                },
                label = { Text(stringResource(R.string.searcher_filter_size_value_label)) },
                placeholder = { Text("e.g. 100 MB") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = sizeError,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = if (isInputValid) ImeAction.Done else ImeAction.None,
                ),
                keyboardActions = KeyboardActions(onDone = { handleApply() }),
            )
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
                onClick = handleApply,
                modifier = Modifier.weight(1f),
                enabled = isInputValid,
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_apply_action))
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SizeConditionEditSheetNewPreview() {
    SizeConditionEditSheet(
        visible = true,
        existingCondition = null,
        onDismiss = {},
        onApply = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SizeConditionEditSheetEditingPreview() {
    SizeConditionEditSheet(
        visible = true,
        existingCondition = FilterCondition.Size(
            comparator = FilterComparator.GTE,
            bytes = 100L * 1024 * 1024,
        ),
        onDismiss = {},
        onApply = {},
    )
}
