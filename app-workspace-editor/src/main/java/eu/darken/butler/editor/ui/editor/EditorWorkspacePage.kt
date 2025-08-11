package eu.darken.butler.editor.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.MemoryStats
import eu.darken.butler.editor.core.SearchResult
import eu.darken.butler.editor.core.TextPosition
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign


@Composable
fun EditorWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: EditorWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: EditorWorkspaceViewModel.Factory ->
            factory.create(id)
        }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(null)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { state ->
        EditorWorkspacePage(
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = workspaceButtonVm::onWorkspaceAction,
            onNavToWorkspaceManager = workspaceButtonVm::onNavToWorkspaceManager,
            design = design,
            state = state,
            onOpenFile = vm::openFile,
            onSaveFile = vm::saveFile,
            onCloseFile = vm::closeFile,
            onTextChange = vm::insertText,
            onCursorPositionChange = vm::setCursorPosition,
            onSelectionChange = { selection ->
                if (selection != null) {
                    vm.setSelection(selection.first, selection.second)
                } else {
                    vm.setCursorPosition(state.cursorPosition)
                }
            },
            onVisibleRangeChange = { range ->
                vm.updateVisibleRange(range.first, range.last)
            },
            onSearch = vm::search,
            onGoToLine = vm::goToLine,
            onUndo = vm::undo,
            onRedo = vm::redo,
            onClearError = vm::clearError,
        )
    }
}

@Composable
fun EditorWorkspacePage(
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
    design: WorkspaceDesign,
    state: EditorWorkspaceViewModel.State,
    onOpenFile: (APath) -> Unit,
    onSaveFile: () -> Unit,
    onCloseFile: () -> Unit,
    onTextChange: (String) -> Unit,
    onCursorPositionChange: (TextPosition) -> Unit,
    onSelectionChange: (Pair<TextPosition, TextPosition>?) -> Unit,
    onVisibleRangeChange: (IntRange) -> Unit,
    onSearch: (String) -> Unit,
    onGoToLine: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearError: () -> Unit,
) {
    rememberCoroutineScope()
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showMemoryStats by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri: Uri ->
            // For now, create a SAFPath from the URI - this is a simplified approach
            try {
                val path = SAFPath(treeRoot = selectedUri.toString(), segments = emptyList())
                onOpenFile(path)
            } catch (e: Exception) {
                // Handle invalid URI format
                log("EditorWorkspacePage") { "Failed to create path from URI: $selectedUri" }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Header that draws under status bar
        EditorHeader(
            design = design,
            fileName = if (state.hasFile) state.fileName else stringResource(R.string.editor_file_untitled),
            isModified = state.isModified,
            hasFile = state.hasFile || state.currentContent.isNotEmpty(),
            isLoading = state.isLoading,
            onOpenFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            onSaveFile = onSaveFile,
            onCloseFile = onCloseFile,
            onUndo = onUndo,
            onRedo = onRedo,
            canUndo = state.isModified,
            canRedo = false,
            onSearch = { showSearchDialog = true },
            onGoToLine = { showGoToLineDialog = true },
            onToggleMemoryStats = { showMemoryStats = !showMemoryStats },
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = onWorkspaceAction,
            onNavToWorkspaceManager = onNavToWorkspaceManager
        )

        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Error display
            state.error?.let { error ->
                ErrorBanner(
                    error = error,
                    onDismiss = onClearError
                )
            }


            // Main editor content - now using fixed LazyTextEditor
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.hasWorkspace) {
                    LazyTextEditor(
                        content = state.currentContent,
                        cursorPosition = state.cursorPosition,
                        selection = state.selectionRange,
                        visibleRange = state.visibleRange,
                        showLineNumbers = state.showLineNumbers,
                        wordWrap = state.wordWrap,
                        fontSize = 14,
                        tabSize = 4,
                        onTextChange = onTextChange,
                        onCursorPositionChange = onCursorPositionChange,
                        onSelectionChange = onSelectionChange,
                        onVisibleRangeChange = onVisibleRangeChange,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Show loading or error state when workspace is not available
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Search results
            if (state.hasSearchResults) {
                SearchResultsBar(
                    searchResults = state.searchResults,
                    currentIndex = 0,
                    onNavigateToResult = { result ->
                        onCursorPositionChange(result.position)
                    },
                    onClose = { onSearch("") }
                )
            }
        }

        // Bottom status bar
        if (showMemoryStats) {
            EditorStatusBar(
                totalLines = state.totalLines,
                cursorPosition = state.cursorPosition,
                memoryStats = state.memoryStats
            )
        }
    }

    // Dialogs
    if (showGoToLineDialog) {
        GoToLineDialog(
            totalLines = state.totalLines,
            onGoToLine = { line ->
                onGoToLine(line)
                showGoToLineDialog = false
            },
            onDismiss = { showGoToLineDialog = false }
        )
    }

    if (showSearchDialog) {
        SearchDialog(
            onSearch = { query ->
                onSearch(query)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
    }
}

@Composable
private fun EditorHeader(
    design: WorkspaceDesign,
    fileName: String,
    isModified: Boolean,
    hasFile: Boolean,
    isLoading: Boolean,
    onOpenFile: () -> Unit,
    onSaveFile: () -> Unit,
    onCloseFile: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onSearch: () -> Unit,
    onGoToLine: () -> Unit,
    onToggleMemoryStats: () -> Unit,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Title section on top
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isModified) {
                    Text(
                        text = stringResource(R.string.editor_modified_indicator),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                if (design.isSingle) {
                    Spacer(modifier = Modifier.width(8.dp))

                    WorkspaceButton(
                        state = workspaceButtonState,
                        onAction = onWorkspaceAction,
                        onNavToWorkspaceManager = onNavToWorkspaceManager,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Actions section below
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(horizontal = 8.dp)
                    )
                }

                IconButton(onClick = onOpenFile) {
                    Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.editor_action_open))
                }

                // Show save/edit actions when there's content or a file
                if (hasFile) {
                    IconButton(
                        onClick = onSaveFile,
                        enabled = isModified
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.editor_action_save))
                    }

                    IconButton(onClick = onCloseFile) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.editor_action_close))
                    }

                    IconButton(
                        onClick = onUndo,
                        enabled = canUndo
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.editor_action_undo)
                        )
                    }

                    IconButton(
                        onClick = onRedo,
                        enabled = canRedo
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.editor_action_redo)
                        )
                    }

                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.editor_action_search))
                    }

                    IconButton(onClick = onGoToLine) {
                        Icon(
                            Icons.Default.FormatListNumbered,
                            contentDescription = stringResource(R.string.editor_action_go_to_line)
                        )
                    }
                }

                IconButton(onClick = onToggleMemoryStats) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.editor_action_toggle_stats))
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EditorStatusBar(
    totalLines: Int,
    cursorPosition: TextPosition,
    memoryStats: MemoryStats
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(
                    R.string.editor_status_line_format,
                    cursorPosition.line + 1,
                    cursorPosition.column + 1
                ),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = stringResource(R.string.editor_status_total_lines, totalLines),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = stringResource(
                    R.string.editor_status_memory,
                    memoryStats.currentUsage / (1024 * 1024),
                    memoryStats.maxMemory / (1024 * 1024),
                    memoryStats.totalChunks
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
private fun ErrorBanner(
    error: Throwable,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(Modifier.width(16.dp))

            Text(
                text = error.message ?: stringResource(R.string.editor_error_unknown),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.editor_action_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun SearchResultsBar(
    searchResults: List<SearchResult>,
    currentIndex: Int,
    onNavigateToResult: (SearchResult) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.editor_search_results_format, currentIndex + 1, searchResults.size),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = {
                    if (currentIndex > 0) {
                        onNavigateToResult(searchResults[currentIndex - 1])
                    }
                },
                enabled = currentIndex > 0
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.editor_action_previous),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            IconButton(
                onClick = {
                    if (currentIndex < searchResults.size - 1) {
                        onNavigateToResult(searchResults[currentIndex + 1])
                    }
                },
                enabled = currentIndex < searchResults.size - 1
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.editor_action_next),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.editor_action_close),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun GoToLineDialog(
    totalLines: Int,
    onGoToLine: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var lineNumber by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_dialog_go_to_line_title)) },
        text = {
            OutlinedTextField(
                value = lineNumber,
                onValueChange = { lineNumber = it.filter { char -> char.isDigit() } },
                label = { Text(stringResource(R.string.editor_dialog_go_to_line_label, totalLines)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    lineNumber.toIntOrNull()?.let { line ->
                        if (line in 1..totalLines) {
                            onGoToLine(line - 1) // Convert to 0-based index
                        }
                    }
                },
                enabled = lineNumber.toIntOrNull()?.let { it in 1..totalLines } == true
            ) {
                Text(stringResource(R.string.editor_dialog_action_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_dialog_action_cancel))
            }
        }
    )
}

@Composable
private fun SearchDialog(
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_dialog_search_title)) },
        text = {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.editor_dialog_search_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSearch(searchQuery) },
                enabled = searchQuery.isNotEmpty()
            ) {
                Text(stringResource(R.string.editor_action_search))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_dialog_action_cancel))
            }
        }
    )
}

@Preview2
@Composable
private fun EditorPagePreview() {
    PreviewWrapper {
        EditorWorkspacePage(
            design = WorkspaceDesign(),
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
            state = EditorWorkspaceViewModel.State(
                id = Workspace.Id(),
                totalLines = 1000,
                isModified = true,
                currentContent = "Sample text content\nLine 2\nLine 3",
                memoryStats = MemoryStats(
                    currentUsage = 10 * 1024 * 1024,
                    maxMemory = 100 * 1024 * 1024,
                    totalChunks = 5,
                    dirtyChunks = 2,
                    usagePercentage = 10
                )
            ),
            onOpenFile = {},
            onSaveFile = {},
            onCloseFile = {},
            onTextChange = {},
            onCursorPositionChange = {},
            onSelectionChange = {},
            onVisibleRangeChange = {},
            onSearch = {},
            onGoToLine = {},
            onUndo = {},
            onRedo = {},
            onClearError = {}
        )
    }
}
