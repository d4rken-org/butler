package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.CreateNewFolder
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel

@Composable
fun ExplorerBottomBar(
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean,
    viewMode: ExplorerWorkspaceViewModel.ViewMode,
    onCreateFolderClick: () -> Unit,
    onCopyClick: () -> Unit,
    onCutClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    onToggleViewMode: () -> Unit,
    onMoreClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isSelectionMode) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
                if (isSelectionMode) {
                    IconButton(onClick = onCopyClick) {
                        Icon(
                            imageVector = Icons.TwoTone.ContentCopy,
                            contentDescription = "Copy",
                        )
                    }
                    IconButton(onClick = onCutClick) {
                        Icon(
                            imageVector = Icons.TwoTone.ContentCut,
                            contentDescription = "Cut",
                        )
                    }
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.TwoTone.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = onShareClick) {
                        Icon(
                            imageVector = Icons.TwoTone.Share,
                            contentDescription = "Share",
                        )
                    }
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.TwoTone.MoreVert,
                            contentDescription = "More options",
                        )
                    }
                } else {
                    IconButton(onClick = onCreateFolderClick) {
                        Icon(
                            imageVector = Icons.TwoTone.CreateNewFolder,
                            contentDescription = "Create new folder",
                        )
                    }
                    IconButton(onClick = onSortClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.Sort,
                            contentDescription = "Sort",
                        )
                    }
                    IconButton(onClick = onFilterClick) {
                        Icon(
                            imageVector = Icons.TwoTone.FilterList,
                            contentDescription = "Filter",
                        )
                    }
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            imageVector = if (viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
                                Icons.TwoTone.GridView
                            } else {
                                Icons.AutoMirrored.TwoTone.ViewList
                            },
                            contentDescription = if (viewMode == ExplorerWorkspaceViewModel.ViewMode.LIST) {
                                "Switch to grid view"
                            } else {
                                "Switch to list view"
                            },
                        )
                    }
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.TwoTone.MoreVert,
                            contentDescription = "More options",
                        )
                    }
            }
        }
    }
}

@Preview2
@Composable
fun ExplorerBottomBarNormalModePreview() {
    PreviewWrapper {
        ExplorerBottomBar(
            isSelectionMode = false,
            viewMode = ExplorerWorkspaceViewModel.ViewMode.LIST,
            onCreateFolderClick = {},
            onCopyClick = {},
            onCutClick = {},
            onDeleteClick = {},
            onShareClick = {},
            onSortClick = {},
            onFilterClick = {},
            onToggleViewMode = {},
            onMoreClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarSelectionModePreview() {
    PreviewWrapper {
        ExplorerBottomBar(
            isSelectionMode = true,
            viewMode = ExplorerWorkspaceViewModel.ViewMode.GRID,
            onCreateFolderClick = {},
            onCopyClick = {},
            onCutClick = {},
            onDeleteClick = {},
            onShareClick = {},
            onSortClick = {},
            onFilterClick = {},
            onToggleViewMode = {},
            onMoreClick = {},
        )
    }
}