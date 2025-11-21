package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.editor.R
import eu.darken.butler.workspace.ui.InfoChip
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun EditorInfoBar(
    modifier: Modifier = Modifier,
    fileSize: Long? = null,
    totalLines: Int = 0,
    totalCharacters: Int = 0,
    cursorLine: Int = 0,
    cursorColumn: Int = 0,
    encoding: String? = null,
    selectedCharacterCount: Int = 0,
    onClearSelection: () -> Unit = {},
) {
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedCharacterCount,
        onClearSelection = if (selectedCharacterCount > 0) onClearSelection else null,
        leadingContent = {
            // Show file size if available
            if (fileSize != null && selectedCharacterCount == 0) {
                InfoChip(
                    icon = Icons.TwoTone.Storage,
                    label = formatFileSize(fileSize),
                )
            }

            // Show total lines
            if (totalLines > 0 && selectedCharacterCount == 0) {
                InfoChip(
                    icon = Icons.TwoTone.Description,
                    label = pluralStringResource(
                        R.plurals.editor_infobar_total_lines,
                        totalLines,
                        totalLines
                    ),
                )
            }

            // Show total characters
            if (totalCharacters > 0 && selectedCharacterCount == 0) {
                InfoChip(
                    icon = Icons.TwoTone.TextFields,
                    label = pluralStringResource(
                        R.plurals.editor_infobar_total_characters,
                        totalCharacters,
                        totalCharacters
                    ),
                )
            }

            // Show cursor position
            if (selectedCharacterCount == 0) {
                InfoChip(
                    icon = Icons.TwoTone.TextFields,
                    label = stringResource(
                        R.string.editor_infobar_cursor_position,
                        cursorLine + 1, // Display as 1-based
                        cursorColumn + 1
                    ),
                )
            }
        },
        trailingContent = {
            Spacer(modifier = Modifier.weight(1f))

            // Show encoding
            if (encoding != null && selectedCharacterCount == 0) {
                InfoChip(
                    icon = Icons.TwoTone.Description,
                    label = encoding,
                )
            }
        }
    )
}

@Preview2
@Composable
private fun EditorInfoBarWithFilePreview() {
    PreviewWrapper {
        EditorInfoBar(
            fileSize = 1024L * 512L,
            totalLines = 42,
            totalCharacters = 15234,
            cursorLine = 10,
            cursorColumn = 5,
            encoding = "UTF-8",
            selectedCharacterCount = 0,
        )
    }
}

@Preview2
@Composable
private fun EditorInfoBarWithSelectionPreview() {
    PreviewWrapper {
        EditorInfoBar(
            fileSize = 1024L * 512L,
            totalLines = 42,
            totalCharacters = 15234,
            cursorLine = 10,
            cursorColumn = 5,
            encoding = "UTF-8",
            selectedCharacterCount = 150,
        )
    }
}

@Preview2
@Composable
private fun EditorInfoBarNoFilePreview() {
    PreviewWrapper {
        EditorInfoBar(
            fileSize = null,
            totalLines = 0,
            totalCharacters = 0,
            cursorLine = 0,
            cursorColumn = 0,
            encoding = null,
            selectedCharacterCount = 0,
        )
    }
}
