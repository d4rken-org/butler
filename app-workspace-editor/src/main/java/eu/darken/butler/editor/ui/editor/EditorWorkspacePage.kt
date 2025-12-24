package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.ui.editor.dialogs.CloseConfirmDialog
import eu.darken.butler.editor.ui.editor.dialogs.GoToLineDialog
import eu.darken.butler.editor.ui.editor.elements.EditorActionBar
import eu.darken.butler.editor.ui.editor.elements.EditorInfoBar
import eu.darken.butler.editor.ui.editor.elements.EditorSearchBar
import eu.darken.butler.editor.ui.editor.elements.EditorToolbarCard
import eu.darken.butler.editor.ui.editor.text.LazyTextEditor
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
    NavigationEventHandler(vm, workspaceButtonVm)

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(null)

    val state by waitForState(vm.state)

    state?.let { state ->
        EditorWorkspacePage(
            workspaceId = id,
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceButtonVm,
            design = design,
            state = state,
            onPageAction = vm::onPageAction,
            onActionExecute = vm::executeAction,
            onDismissGoToLineDialog = vm::dismissGoToLineDialog,
            onDismissSearchDialog = vm::dismissSearchDialog,
            onSearchQueryChange = vm::updateSearchQuery,
            onCaseSensitiveToggle = vm::toggleCaseSensitivity,
            onRegexToggle = vm::toggleRegexMode,
            onWholeWordToggle = vm::toggleWholeWord,
            onNextSearchResult = vm::nextSearchResult,
            onPreviousSearchResult = vm::previousSearchResult,
            onCloseSearch = vm::closeSearch,
            onDismissCloseConfirmDialog = vm::dismissCloseConfirmDialog,
            onConfirmCloseFile = vm::confirmCloseFile,
        )
    }
}

@Composable
fun EditorWorkspacePage(
    workspaceId: Workspace.Id,
    workspaceButtonState: WorkspaceButtonViewModel.State? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    design: WorkspaceDesign,
    state: EditorWorkspaceViewModel.State,
    onPageAction: (EditorPageAction) -> Unit,
    onActionExecute: (EditorAction) -> Unit = {},
    onDismissGoToLineDialog: () -> Unit = {},
    onDismissSearchDialog: () -> Unit = {},
    onSearchQueryChange: (TextFieldValue) -> Unit = {},
    onCaseSensitiveToggle: () -> Unit = {},
    onRegexToggle: () -> Unit = {},
    onWholeWordToggle: () -> Unit = {},
    onNextSearchResult: () -> Unit = {},
    onPreviousSearchResult: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
    onDismissCloseConfirmDialog: () -> Unit = {},
    onConfirmCloseFile: () -> Unit = {},
) {
    rememberCoroutineScope()

    val hasActions = state.availableActions.isNotEmpty()

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

                // Main editor content - conditional rendering based on file state
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (!state.hasFile && state.isLoading) {
                        // Initial file load - show centered loading overlay
                        EditorLoadingOverlay(
                            onCancel = { onPageAction(EditorPageAction.File.CancelOpen) }
                        )
                    } else {
                        // Show editor (with file content or empty in-memory buffer)
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
                            searchResults = state.searchResults,
                            currentSearchResultIndex = state.currentSearchResultIndex,
                            scrollTrigger = state.scrollTrigger,
                            onTextChange = { text -> onPageAction(EditorPageAction.Edit.InsertText(text)) },
                            onTextDelete = { count -> onPageAction(EditorPageAction.Edit.DeleteAtCursor(count)) },
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
                            onCursorMove = { direction, extendSelection ->
                                onPageAction(EditorPageAction.Navigation.MoveCursor(direction, extendSelection))
                            },
                            onForwardDelete = {
                                onPageAction(EditorPageAction.Edit.ForwardDelete)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

            }
        }

        // Floating toolbar card and info bar at top
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onGloballyPositioned { layoutCoordinates ->
                    actualToolbarHeightPx = layoutCoordinates.size.height
                }
        ) {
            EditorToolbarCard(
                workspaceId = workspaceId,
                design = design,
                title = state.title,
                subTitle = state.subTitle,
                isModified = state.isModified,
                isLoading = state.isLoading,
                hasContent = state.hasContent,
                canUndo = state.isModified,
                canRedo = false,
                workspaceButtonState = workspaceButtonState,
                workspaceActionHandler = workspaceActionHandler,
                onAction = onPageAction,
                collapsedFraction = topToolbarScrollBehavior.state.collapsedFraction,
            )

            // Info bar below toolbar
            EditorInfoBar(
                modifier = Modifier.padding(top = 8.dp),
                fileSize = state.fileSize,
                totalLines = state.totalLines,
                cursorLine = state.cursorPosition.line,
                cursorColumn = state.cursorPosition.column,
                selectedLineCount = state.selectedLineCount,
                selectedCharacterCount = state.selectedCharacterCount,
                onClearSelection = {
                    onPageAction(EditorPageAction.Navigation.ClearSelection(state.cursorPosition))
                },
            )
        }

        // Floating Search Bar (above action bar)
        if (state.isSearchBarVisible) {
            EditorSearchBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .padding(
                        bottom = if (hasActions && bottomBarScrollBehavior.state.collapsedFraction <= 0.1f) {
                            64.dp  // Action bar visible - offset above it
                        } else {
                            0.dp   // Action bar hidden or no actions - sit at bottom
                        }
                    ),
                scrollState = bottomBarScrollBehavior.state,
                searchQuery = state.searchQueryInput,
                searchResults = state.searchResults,
                currentIndex = state.currentSearchResultIndex,
                caseSensitive = state.searchCaseSensitive,
                regexEnabled = state.searchRegexEnabled,
                wholeWord = state.searchWholeWord,
                onSearchQueryChange = onSearchQueryChange,
                onCaseSensitiveToggle = onCaseSensitiveToggle,
                onRegexToggle = onRegexToggle,
                onWholeWordToggle = onWholeWordToggle,
                onPrevious = onPreviousSearchResult,
                onNext = onNextSearchResult,
                onClose = onCloseSearch,
            )
        }

        // Floating Bottom ActionBar
        if (hasActions) {
            EditorActionBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                actions = state.availableActions,
                scrollState = bottomBarScrollBehavior.state,
                onActionClick = onActionExecute,
            )
        }
    }

    // Dialogs
    if (state.showGoToLineDialog) {
        GoToLineDialog(
            totalLines = state.totalLines,
            onGoToLine = { line ->
                onPageAction(EditorPageAction.Navigation.GoToLine(line))
                onDismissGoToLineDialog()
            },
            onDismiss = onDismissGoToLineDialog,
        )
    }

    if (state.showCloseConfirmDialog) {
        CloseConfirmDialog(
            onConfirm = onConfirmCloseFile,
            onDismiss = onDismissCloseConfirmDialog,
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
private fun EditorLoadingOverlay(
    modifier: Modifier = Modifier,
    fileName: String? = null,
    onCancel: () -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = fileName ?: stringResource(R.string.editor_loading_file),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.editor_action_cancel_loading))
            }
        }
    }
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
                title = caString("test.txt"),
                subTitle = caString("/sdcard/test.txt"),
                totalLines = 1000,
                isModified = true,
                currentContent = "Sample text content\nLine 2\nLine 3",
            ),
            onPageAction = {}
        )
    }
}

@Preview2
@Composable
private fun EditorLoadingOverlayPreview() {
    PreviewWrapper {
        EditorLoadingOverlay()
    }
}
