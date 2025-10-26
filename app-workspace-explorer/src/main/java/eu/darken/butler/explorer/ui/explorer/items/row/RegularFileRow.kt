package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatDate
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.isProblematicInvisible
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun RegularFileRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.RegularFile,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean,
) {
    val primaryText = item.displayName.get(LocalContext.current)
    val hasProblematicChars = primaryText.trim { it.isProblematicInvisible() } != primaryText

    FileRowBase(
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        modifier = modifier,
        leadingContent = {
            TintedAsyncImage(
                model = item.lookup,
                contentDescription = stringResource(R.string.explorer_file_regular_content_desc),
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = primaryText,
        hasProblematicChars = hasProblematicChars,
        secondaryText = buildString {
            append(item.lookup.size?.let { formatFileSize(it) } ?: "?")
            append(" • ")
            append(item.lookup.modifiedAt?.let { formatDate(it) } ?: "?")
            item.permissions?.let { perms ->
                append(" • ")
                append(perms.toReadableString())
            }
            item.ownership?.let { owner ->
                append(" • ")
                append(owner.userName ?: owner.userId)
                append(" | ")
                append(owner.groupName ?: owner.groupId)
            }
        }
    )
}

@Preview2
@Composable
private fun RegularFileRowPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun RegularFileRowSelectedPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile("config.json"),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}

@Preview2
@Composable
private fun RegularFileRowLeadingWhitespacePreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile(" document.txt"),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun RegularFileRowTrailingWhitespaceSelectedPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile("report.pdf "),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}