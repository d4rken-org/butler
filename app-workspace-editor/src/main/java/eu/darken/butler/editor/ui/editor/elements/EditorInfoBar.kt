package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.editor.R
import eu.darken.butler.editor.ui.editor.text.toIntSaturated
import eu.darken.butler.workspace.ui.InfoChip
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun EditorInfoBar(
    modifier: Modifier = Modifier,
    fileSize: Long? = null,
    totalLines: Long = 0,
    cursorLine: Long = 0,
    cursorColumn: Int = 0,
    selectedCharacterCount: Long = 0,
    selectedLineCount: Long = 0,
    onClearSelection: () -> Unit = {},
) {
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedLineCount.toIntSaturated(),
        onClearSelection = onClearSelection,
        selectionText = {
            val lines = pluralStringResource(
                R.plurals.editor_infobar_lines,
                selectedLineCount.toIntSaturated(),
                selectedLineCount
            )
            val characters = pluralStringResource(
                R.plurals.editor_infobar_characters,
                selectedCharacterCount.toIntSaturated(),
                selectedCharacterCount
            )
            stringResource(
                R.string.editor_infobar_selected_x_y,
                lines,
                characters
            )
        },
        leadingContent = {
            if (selectedCharacterCount == 0L) {
                InfoChip(
                    icon = Icons.TwoTone.TextFields,
                    label = "${cursorLine + 1}:${cursorColumn + 1}",
                )
            }
        },
        trailingContent = {
            Spacer(modifier = Modifier.weight(1f))

            // Show combined lines and size
            if (totalLines > 0 && fileSize != null && selectedCharacterCount == 0L) {
                InfoChip(
                    icon = Icons.TwoTone.Description,
                    label = stringResource(
                        R.string.editor_infobar_lines_size,
                        totalLines,
                        formatFileSize(fileSize),
                    ),
                )
            }
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorInfoBarWithFilePreview() {
    EditorInfoBar(
        fileSize = 1024L * 512L,
        totalLines = 42,
        cursorLine = 10,
        cursorColumn = 5,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorInfoBarWithSelectionPreview() {
    EditorInfoBar(
        fileSize = 1024L * 512L,
        totalLines = 42,
        cursorLine = 10,
        cursorColumn = 5,
        selectedLineCount = 10,
        selectedCharacterCount = 150,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorInfoBarNoFilePreview() {
    EditorInfoBar()
}
