package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.R
import eu.darken.butler.editor.ui.editor.dialogs.CloseConfirmDialog
import eu.darken.butler.editor.ui.editor.dialogs.GoToLineDialog
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import eu.darken.butler.editor.ui.editor.elements.EditorActionBar
import eu.darken.butler.editor.ui.editor.elements.EditorInfoBar
import eu.darken.butler.editor.ui.editor.elements.EditorSearchBar
import eu.darken.butler.editor.ui.editor.elements.EditorToolbarCard
import eu.darken.butler.editor.ui.editor.text.LazyTextEditor
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf


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
    val clipboardInfoClip by vm.clipboardInfoClip.collectAsState(null)

    state?.let { state ->
        EditorWorkspacePage(
            workspaceId = id,
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceButtonVm,
            design = design,
            state = state,
            clipboardStateSource = vm.clipboard,
            onPageAction = vm::onPageAction,
            onActionExecute = vm::executeAction,
            onActionLongClick = vm::executeActionLongClick,
            onClipboardPaste = vm::pasteFromClipboard,
            onClipboardRemove = vm::removeClipboardEntry,
            onClipboardClear = vm::clearAllClipboard,
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
            clipboardInfoClip = clipboardInfoClip,
            onClipboardEntryClick = vm::showClipboardInfo,
            onDismissClipboardInfo = vm::dismissClipboardInfo,
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
    clipboardStateSource: Flow<EditorWorkspaceViewModel.ClipboardState> = flowOf(EditorWorkspaceViewModel.ClipboardState()),
    onPageAction: (EditorPageAction) -> Unit,
    onActionExecute: (EditorAction) -> Unit = {},
    onActionLongClick: (EditorAction) -> Unit = {},
    onClipboardPaste: (ClipboardClip) -> Unit = {},
    onClipboardRemove: (ClipboardClip) -> Unit = {},
    onClipboardClear: () -> Unit = {},
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
    clipboardInfoClip: ClipboardClip? = null,
    onClipboardEntryClick: (ClipboardClip) -> Unit = {},
    onDismissClipboardInfo: () -> Unit = {},
) {
    val clipboardState by clipboardStateSource.collectAsState(EditorWorkspaceViewModel.ClipboardState())

    val hasClipboard by remember {
        derivedStateOf { clipboardState.entries.isNotEmpty() }
    }
    val hasActions = state.availableActions.isNotEmpty()

    val topBarStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        includeSystemBarInset = design.paneEdges.touchesTop,
    )
    val bottomBarStackState = rememberFloatingBarStackState(
        position = BarPosition.BOTTOM,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        includeSystemBarInset = design.paneEdges.touchesBottom,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Top floating bars
        FloatingBarStack(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            position = BarPosition.TOP,
            state = topBarStackState,
            bars = {
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(collapsedHeight = 48.dp),
                    estimatedHeight = 80.dp, // EditorToolbarCard expanded height
                    animation = BarAnimation.Slide(),
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
                        collapsedFraction = collapsedFraction,
                    )
                }
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    estimatedHeight = 24.dp, // InfoChip height
                    animation = BarAnimation.Slide(),
                ) {
                    EditorInfoBar(
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
            },
        ) { _ -> }

        // Bottom floating bars
        FloatingBarStack(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            position = BarPosition.BOTTOM,
            state = bottomBarStackState,
            bars = {
                FloatingBar(
                    visible = state.isSearchBarVisible,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                ) {
                    EditorSearchBar(
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
                FloatingBar(
                    visible = hasClipboard,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    animation = BarAnimation.Bouncy,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    ClipboardBar(
                        workspaceType = Workspace.Type.EDITOR,
                        clipboardEntries = clipboardState.entries,
                        onPasteClick = onClipboardPaste,
                        onRemoveClick = onClipboardRemove,
                        onEntryClick = onClipboardEntryClick,
                        onClearAll = onClipboardClear,
                    )
                }
                FloatingBar(
                    visible = hasActions,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                ) {
                    EditorActionBar(
                        actions = state.availableActions,
                        onActionClick = onActionExecute,
                        onActionLongClick = onActionLongClick,
                    )
                }
            },
        ) { _ -> }

        // Main content - composes after bars so contentPaddingDp() has correct values
        Column(
            modifier = Modifier.fillMaxSize()
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
                            contentPadding = PaddingValues(
                                top = topBarStackState.contentPaddingDp(),
                                bottom = bottomBarStackState.contentPaddingDp(),
                            ),
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
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(topBarStackState.nestedScrollConnection)
                                .nestedScroll(bottomBarStackState.nestedScrollConnection),
                        )
                    }
                }

            }
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

    clipboardInfoClip?.let { clip ->
        ClipboardInfoBottomSheet(
            clip = clip,
            onDismiss = onDismissClipboardInfo,
            onNavigateToSource = null,
            onPaste = { onClipboardPaste(clip) },
            onRemove = { onClipboardRemove(clip) },
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
