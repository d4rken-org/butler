package eu.darken.butler.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace

@Composable
fun ExplorerWorkspacePageHost(
    id: Workspace.Id,
    vm: ExplorerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ExplorerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }
    state?.let { state ->
        ExplorerWorkspacePage(
            state = state,
        )
    }
}

@Composable
fun ExplorerWorkspacePage(
    state: ExplorerWorkspaceViewModel.State,
    initialPath: String = "/"
) {
    // Mock data for the file explorer
    val rootFolder = remember {
        ExplorerItem.Folder(
            name = "Root",
            path = "/",
            children = listOf(
                ExplorerItem.Folder(
                    name = "Documents",
                    path = "/Documents",
                    children = listOf(
                        ExplorerItem.File(
                            name = "Resume.pdf",
                            path = "/Documents/Resume.pdf",
                            size = "1.2 MB",
                            lastModified = "2023-10-15"
                        ),
                        ExplorerItem.File(
                            name = "Project Plan.docx",
                            path = "/Documents/Project Plan.docx",
                            size = "458 KB",
                            lastModified = "2023-10-20"
                        )
                    )
                ),
                ExplorerItem.Folder(
                    name = "Pictures",
                    path = "/Pictures",
                    children = listOf(
                        ExplorerItem.File(
                            name = "Vacation.jpg",
                            path = "/Pictures/Vacation.jpg",
                            size = "3.5 MB",
                            lastModified = "2023-09-05"
                        ),
                        ExplorerItem.File(
                            name = "Family.png",
                            path = "/Pictures/Family.png",
                            size = "2.8 MB",
                            lastModified = "2023-08-12"
                        )
                    )
                ),
                ExplorerItem.File(
                    name = "Notes.txt",
                    path = "/Notes.txt",
                    size = "12 KB",
                    lastModified = "2023-10-25"
                ),
                ExplorerItem.File(
                    name = "Budget.xlsx",
                    path = "/Budget.xlsx",
                    size = "345 KB",
                    lastModified = "2023-10-18"
                )
            )
        )
    }

    // Find the folder corresponding to the initialPath
    val initialFolder = remember(rootFolder, initialPath) {
        if (initialPath == "/") {
            rootFolder
        } else {
            findFolderByPath(rootFolder, initialPath) ?: rootFolder
        }
    }

    // Build navigation history
    val initialHistory = remember(rootFolder, initialFolder) {
        if (initialFolder == rootFolder) {
            listOf(rootFolder)
        } else {
            buildNavigationHistory(rootFolder, initialFolder)
        }
    }

    var currentFolder by remember(initialFolder) { mutableStateOf(initialFolder) }
    var currentPath by remember(initialPath) {
        mutableStateOf(if (findFolderByPath(rootFolder, initialPath) != null) initialPath else "/")
    }
    var navigationHistory by remember(initialHistory) { mutableStateOf(initialHistory) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Path display
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Current path: $currentPath",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Back navigation
        if (navigationHistory.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable {
                        navigationHistory = navigationHistory.dropLast(1)
                        currentFolder = navigationHistory.last()
                        currentPath = currentFolder.path
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⬅️ Back to ${navigationHistory.dropLast(1).last().name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                thickness = DividerDefaults.Thickness,
                color = DividerDefaults.color
            )
        }

        // File list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            // Folders first
            items(currentFolder.children.filterIsInstance<ExplorerItem.Folder>()) { folder ->
                ExplorerItemRow(
                    item = folder,
                    onClick = {
                        currentFolder = folder
                        currentPath = folder.path
                        navigationHistory = navigationHistory + folder
                    }
                )
            }

            // Then files
            items(currentFolder.children.filterIsInstance<ExplorerItem.File>()) { file ->
                ExplorerItemRow(
                    item = file,
                    onClick = { /* Do nothing for files */ }
                )
            }

            // Empty state
            if (currentFolder.children.isEmpty()) {
                item {
                    Text(
                        text = "This folder is empty",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
        }
    }
}

// Data model for file explorer items
sealed class ExplorerItem(
    open val name: String,
    open val path: String,
) {
    data class Folder(
        override val name: String,
        override val path: String,
        val children: List<ExplorerItem> = emptyList()
    ) : ExplorerItem(name, path)

    data class File(
        override val name: String,
        override val path: String,
        val size: String,
        val lastModified: String
    ) : ExplorerItem(name, path)
}

// Helper function to find a folder by path in the mock data structure
private fun findFolderByPath(rootFolder: ExplorerItem.Folder, targetPath: String): ExplorerItem.Folder? {
    if (rootFolder.path == targetPath) {
        return rootFolder
    }

    rootFolder.children.filterIsInstance<ExplorerItem.Folder>().forEach { folder ->
        val found = findFolderByPath(folder, targetPath)
        if (found != null) {
            return found
        }
    }

    return null
}

// Helper function to build navigation history to a folder
private fun buildNavigationHistory(
    rootFolder: ExplorerItem.Folder,
    targetFolder: ExplorerItem.Folder
): List<ExplorerItem.Folder> {
    if (rootFolder == targetFolder) {
        return listOf(rootFolder)
    }

    rootFolder.children.filterIsInstance<ExplorerItem.Folder>().forEach { folder ->
        val history = buildNavigationHistory(folder, targetFolder)
        if (history.isNotEmpty()) {
            return listOf(rootFolder) + history
        }
    }

    return emptyList()
}

@Composable
fun ExplorerItemRow(
    item: ExplorerItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon based on item type
            when (item) {
                is ExplorerItem.Folder -> {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                is ExplorerItem.File -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = "File",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Item details
            Column {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (item is ExplorerItem.File) {
                    Text(
                        text = "${item.size} • Last modified: ${item.lastModified}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun ExplorerPagePreview() {
    PreviewWrapper {
        ExplorerWorkspacePage(
            state = ExplorerWorkspaceViewModel.State(
                id = Workspace.Id()
            )
        )
    }
}