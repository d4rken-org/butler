package eu.darken.butler.explorer.ui.browser.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.ui.browser.FileItem
import eu.darken.butler.explorer.ui.browser.preview.MockDataProvider

@Composable
internal fun DocumentFileRow(
    item: FileItem.DocumentFile,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    showSelection: Boolean,
    modifier: Modifier = Modifier
) {
    FileRowBase(
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        showSelection = showSelection,
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = "Document",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(40.dp)
            )
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            item.pageCount?.let { 
                append("$it pages")
                append(" • ")
            }
            append(item.displaySize)
            append(" • ")
            append(item.displayDate)
        }
    )
}

@Preview2
@Composable
private fun DocumentFileRowPreview() {
    DocumentFileRow(
        item = MockDataProvider.createMockDocumentFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun DocumentFileRowSelectedPreview() {
    DocumentFileRow(
        item = MockDataProvider.createMockDocumentFile("manual.pdf", 128, "Butler Team"),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}