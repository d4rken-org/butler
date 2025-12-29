package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.workspace.ui.InfoChip
import eu.darken.butler.workspace.ui.WorkspaceInfoBar

@Composable
fun SearcherInfoBar(
    modifier: Modifier = Modifier,
    foldersCount: Int = 0,
    filesCount: Int = 0,
    totalSize: Long = 0L,
    selectedCount: Int = 0,
    selectedSize: Long = 0L,
    onSelectAllFolders: () -> Unit = {},
    onSelectAllFiles: () -> Unit = {},
    onClearSelection: () -> Unit = {},
) {
    WorkspaceInfoBar(
        modifier = modifier,
        selectedCount = selectedCount,
        onClearSelection = onClearSelection,
        leadingContent = {
            if (selectedCount == 0) {
                if (foldersCount > 0) {
                    InfoChip(
                        icon = Icons.TwoTone.Folder,
                        label = pluralStringResource(CommonR.plurals.common_folders_count, foldersCount, foldersCount),
                        onClick = onSelectAllFolders,
                    )
                }
                if (filesCount > 0) {
                    InfoChip(
                        icon = Icons.TwoTone.Description,
                        label = pluralStringResource(CommonR.plurals.common_files_count, filesCount, filesCount),
                        onClick = onSelectAllFiles,
                    )
                }
            }
        },
        trailingContent = {
            Spacer(modifier = Modifier.weight(1f))
            if (selectedCount > 0 && selectedSize > 0) {
                InfoChip(
                    icon = Icons.TwoTone.Storage,
                    label = formatFileSize(selectedSize),
                    isAccented = true,
                )
            } else if (selectedCount == 0 && totalSize > 0) {
                InfoChip(
                    icon = Icons.TwoTone.Storage,
                    label = formatFileSize(totalSize),
                )
            }
        },
    )
}

@Preview2
@Composable
private fun SearcherInfoBarWithResultsPreview() {
    PreviewWrapper {
        SearcherInfoBar(
            foldersCount = 12,
            filesCount = 30,
            totalSize = 1024L * 1024L * 512L,
            selectedCount = 0,
            onSelectAllFolders = {},
            onSelectAllFiles = {},
            onClearSelection = {},
        )
    }
}

@Preview2
@Composable
private fun SearcherInfoBarWithSelectionPreview() {
    PreviewWrapper {
        SearcherInfoBar(
            foldersCount = 12,
            filesCount = 30,
            totalSize = 1024L * 1024L * 512L,
            selectedCount = 3,
            selectedSize = 1024L * 1024L * 100L,
            onSelectAllFolders = {},
            onSelectAllFiles = {},
            onClearSelection = {},
        )
    }
}

@Preview2
@Composable
private fun SearcherInfoBarOnlyFoldersPreview() {
    PreviewWrapper {
        SearcherInfoBar(
            foldersCount = 5,
            filesCount = 0,
            totalSize = 1024L * 1024L * 256L,
            selectedCount = 0,
            onSelectAllFolders = {},
            onSelectAllFiles = {},
            onClearSelection = {},
        )
    }
}

@Preview2
@Composable
private fun SearcherInfoBarOnlyFilesPreview() {
    PreviewWrapper {
        SearcherInfoBar(
            foldersCount = 0,
            filesCount = 25,
            totalSize = 1024L * 1024L * 128L,
            selectedCount = 0,
            onSelectAllFolders = {},
            onSelectAllFiles = {},
            onClearSelection = {},
        )
    }
}
