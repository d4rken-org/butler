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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

/**
 * Options for file type filter - simplified to common use cases.
 */
private enum class FileTypeOption(val labelResId: Int, val fileType: FileType) {
    FILES(R.string.searcher_filter_type_files, FileType.FILE),
    DIRECTORIES(R.string.searcher_filter_type_directories, FileType.DIRECTORY),
}

/**
 * Bottom sheet for editing a file type condition.
 */
@Composable
fun TypeConditionEditSheet(
    modifier: Modifier = Modifier,
    visible: Boolean,
    existingCondition: FilterCondition.Type?,
    onDismiss: () -> Unit,
    onApply: (FilterCondition.Type) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = visible,
        onDismiss = onDismiss,
        bottomInset = bottomInset,
    ) {
        TypeConditionEditContent(
            existingCondition = existingCondition,
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

@Composable
private fun TypeConditionEditContent(
    existingCondition: FilterCondition.Type?,
    onDismiss: () -> Unit,
    onApply: (FilterCondition.Type) -> Unit,
) {
    // Map existing file type to option (default to FILES)
    val initialOption = when (existingCondition?.fileType) {
        FileType.DIRECTORY -> FileTypeOption.DIRECTORIES
        else -> FileTypeOption.FILES
    }

    var selectedOption by rememberSaveable(existingCondition) {
        mutableStateOf(initialOption)
    }

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
                text = stringResource(R.string.searcher_filter_type_label),
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

        // File type segmented buttons
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            FileTypeOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selectedOption == option,
                    onClick = { selectedOption = option },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = FileTypeOption.entries.size,
                    ),
                ) {
                    Text(stringResource(option.labelResId))
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
                onClick = { onApply(FilterCondition.Type(selectedOption.fileType)) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_apply_action))
            }
        }
    }
}

@Preview2
@Composable
private fun TypeConditionEditSheetNewPreview() {
    PreviewWrapper {
        TypeConditionEditSheet(
            visible = true,
            existingCondition = null,
            onDismiss = {},
            onApply = {},
        )
    }
}

@Preview2
@Composable
private fun TypeConditionEditSheetEditingPreview() {
    PreviewWrapper {
        TypeConditionEditSheet(
            visible = true,
            existingCondition = FilterCondition.Type(FileType.DIRECTORY),
            onDismiss = {},
            onApply = {},
        )
    }
}
