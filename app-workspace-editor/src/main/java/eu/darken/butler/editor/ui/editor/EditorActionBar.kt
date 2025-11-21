package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R

@Composable
fun EditorActionBar(
    modifier: Modifier = Modifier,
    hasSelection: Boolean = false,
    hasClipboardContent: Boolean = false,
    hasFile: Boolean = false,
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    onPaste: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSelectAll: () -> Unit = {},
) {
    // Only show action bar when there's a selection
    if (!hasSelection) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Copy action
            ActionChip(
                label = stringResource(R.string.editor_action_copy),
                icon = Icons.TwoTone.ContentCopy,
                enabled = hasSelection,
                onClick = onCopy,
            )

            // Cut action
            ActionChip(
                label = stringResource(R.string.editor_action_cut),
                icon = Icons.TwoTone.ContentCut,
                enabled = hasSelection,
                onClick = onCut,
            )

            // Delete action
            ActionChip(
                label = stringResource(R.string.editor_action_delete),
                icon = Icons.TwoTone.Delete,
                enabled = hasSelection,
                onClick = onDelete,
            )

            // Paste action (always visible but enabled based on clipboard)
            ActionChip(
                label = stringResource(R.string.editor_action_paste),
                icon = Icons.TwoTone.ContentPaste,
                enabled = hasClipboardContent,
                onClick = onPaste,
            )

            // Select All action
            ActionChip(
                label = stringResource(R.string.editor_action_select_all),
                icon = Icons.TwoTone.SelectAll,
                enabled = hasFile,
                onClick = onSelectAll,
            )
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(0.dp)
            )
        },
        enabled = enabled,
        modifier = modifier.height(32.dp),
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            labelColor = if (enabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            leadingIconContentColor = if (enabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        ),
    )
}

@Preview2
@Composable
private fun EditorActionBarWithSelectionPreview() {
    PreviewWrapper {
        EditorActionBar(
            hasSelection = true,
            hasClipboardContent = true,
            hasFile = true,
            onCopy = {},
            onCut = {},
            onPaste = {},
            onDelete = {},
            onSelectAll = {},
        )
    }
}

@Preview2
@Composable
private fun EditorActionBarWithSelectionNoClipboardPreview() {
    PreviewWrapper {
        EditorActionBar(
            hasSelection = true,
            hasClipboardContent = false,
            hasFile = true,
            onCopy = {},
            onCut = {},
            onPaste = {},
            onDelete = {},
            onSelectAll = {},
        )
    }
}

@Preview2
@Composable
private fun EditorActionBarNoSelectionPreview() {
    PreviewWrapper {
        EditorActionBar(
            hasSelection = false,
            hasClipboardContent = true,
            hasFile = true,
            onCopy = {},
            onCut = {},
            onPaste = {},
            onDelete = {},
            onSelectAll = {},
        )
    }
}
