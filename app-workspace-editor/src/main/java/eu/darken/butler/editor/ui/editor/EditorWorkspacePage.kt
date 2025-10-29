package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.rememberBottomBarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.rememberTopToolbarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeight
import eu.darken.butler.workspace.ui.scroll.setHeights


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
            workspaceId = id,
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceButtonVm,
            design = design,
            state = state,
            onPageAction = vm::onPageAction,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorWorkspacePage(
    workspaceId: Workspace.Id,
    workspaceButtonState: WorkspaceButtonViewModel.State? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    design: WorkspaceDesign,
    state: EditorWorkspaceViewModel.State,
    onPageAction: (EditorPageAction) -> Unit,
) {
    rememberCoroutineScope()
    var showGoToLineDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    // Setup scroll behavior for collapsing header
    val topToolbarScrollBehavior = rememberTopToolbarScrollBehavior()
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    val density = LocalDensity.current
    var actualToolbarHeightPx by remember { mutableStateOf(0) }
    val actualToolbarHeightDp = with(density) { actualToolbarHeightPx.toDp() }

    // Set the top toolbar heights (expanded and collapsed)
    topToolbarScrollBehavior.state.setHeights(
        expandedHeightDp = 104.dp,  // Full card with title + actions
        collapsedHeightDp = 48.dp   // Compact single row
    )

    // Memory info card height
    val memoryCardHeight = 36.dp

    // Set the bottom bar height for scroll behavior
    bottomBarScrollBehavior.state.setHeight(memoryCardHeight)

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Main content with padding for floating header and memory card
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 16.dp + actualToolbarHeightDp,
                    bottom = 0.dp
                )
                .nestedScroll(topToolbarScrollBehavior.nestedScrollConnection)
                .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection)
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Error display
                state.error?.let { error ->
                    ErrorBanner(
                        error = error,
                        onDismiss = { onPageAction(EditorPageAction.Error.Clear) }
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
                            totalLines = state.totalLines,
                            cursorPosition = state.cursorPosition,
                            selection = state.selectionRange,
                            visibleRange = state.visibleRange,
                            showLineNumbers = state.showLineNumbers,
                            wordWrap = state.wordWrap,
                            fontSize = 14,
                            tabSize = 4,
                            onTextChange = { text -> onPageAction(EditorPageAction.Edit.InsertText(text)) },
                            onCursorPositionChange = { position ->
                                onPageAction(
                                    EditorPageAction.Navigation.SetCursor(
                                        position
                                    )
                                )
                            },
                            onSelectionChange = { selection ->
                                if (selection != null) {
                                    onPageAction(
                                        EditorPageAction.Navigation.SetSelection(
                                            selection.first,
                                            selection.second
                                        )
                                    )
                                } else {
                                    onPageAction(EditorPageAction.Navigation.ClearSelection(state.cursorPosition))
                                }
                            },
                            onVisibleRangeChange = { range ->
                                onPageAction(EditorPageAction.Navigation.UpdateVisibleRange(range.first, range.last))
                            },
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
                            onPageAction(EditorPageAction.Navigation.SetCursor(result.position))
                        },
                        onClose = { onPageAction(EditorPageAction.Navigation.Search("")) }
                    )
                }
            }
        }

        // Floating toolbar card at top
        EditorToolbarCard(
            workspaceId = workspaceId,
            design = design,
            fileName = if (state.hasFile) state.fileName else stringResource(R.string.editor_file_untitled),
            isModified = state.isModified,
            hasFile = state.hasFile || state.currentContent.isNotEmpty(),
            isLoading = state.isLoading,
            canUndo = state.isModified,
            canRedo = false,
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceActionHandler,
            onAction = { action ->
                when (action) {
                    is EditorPageAction.Navigation.Search -> showSearchDialog = true
                    is EditorPageAction.Navigation.GoToLine -> showGoToLineDialog = true
                    else -> onPageAction(action)
                }
            },
            collapsedFraction = topToolbarScrollBehavior.state.collapsedFraction,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    actualToolbarHeightPx = layoutCoordinates.size.height
                }
        )

        EditorInfoCard(
            cursorPosition = state.cursorPosition,
            totalLines = state.totalLines,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .graphicsLayer {
                    // Immediate snap behavior: fully visible or fully hidden
                    alpha = if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) 0f else 1f
                    translationY =
                        if (bottomBarScrollBehavior.state.collapsedFraction > 0.1f) memoryCardHeight.toPx() else 0f
                }
        )
    }

    // Dialogs
    if (showGoToLineDialog) {
        GoToLineDialog(
            totalLines = state.totalLines,
            onGoToLine = { line ->
                onPageAction(EditorPageAction.Navigation.GoToLine(line))
                showGoToLineDialog = false
            },
            onDismiss = { showGoToLineDialog = false }
        )
    }

    if (showSearchDialog) {
        SearchDialog(
            onSearch = { query ->
                onPageAction(EditorPageAction.Navigation.Search(query))
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
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
                Icons.TwoTone.Error,
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
                    Icons.TwoTone.Close,
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
                    Icons.TwoTone.KeyboardArrowUp,
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
                    Icons.TwoTone.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.editor_action_next),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    Icons.TwoTone.Close,
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
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            state = EditorWorkspaceViewModel.State(
                id = Workspace.Id(),
                totalLines = 1000,
                isModified = true,
                currentContent = "Sample text content\nLine 2\nLine 3",
            ),
            onPageAction = {}
        )
    }
}
