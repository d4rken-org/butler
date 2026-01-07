package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.editor.R
import eu.darken.butler.editor.ui.editor.dialogs.CloseConfirmDialog
import eu.darken.butler.editor.ui.editor.dialogs.GoToLineDialog
import eu.darken.butler.editor.ui.editor.elements.EditorActionBar
import eu.darken.butler.editor.ui.editor.elements.EditorLoadingOverlay
import eu.darken.butler.editor.ui.editor.elements.EditorInfoBar
import eu.darken.butler.editor.ui.editor.elements.EditorSearchBar
import eu.darken.butler.editor.ui.editor.elements.EditorToolbarCard
import eu.darken.butler.editor.ui.editor.text.LazyTextEditor
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
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
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val clipboardInfoClip by vm.clipboardInfoClip.collectAsState(null)

    EditorWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        clipboardInfoClip = clipboardInfoClip,
        onPageAction = vm::onPageAction,
        onActionExecute = vm::executeAction,
        onActionLongClick = vm::executeActionLongClick,
    )
}

@Composable
fun EditorWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    mainStateSource: Flow<EditorWorkspaceViewModel.State>,
    clipboardStateSource: Flow<EditorWorkspaceViewModel.ClipboardState> = flowOf(EditorWorkspaceViewModel.ClipboardState()),
    clipboardInfoClip: ClipboardClip? = null,
    onPageAction: (EditorPageAction) -> Unit,
    onActionExecute: (EditorAction) -> Unit = {},
    onActionLongClick: (EditorAction) -> Unit = {},
) {
    val mainState by mainStateSource.collectAsState(EditorWorkspaceViewModel.State.Initializing)
    val clipboardState by clipboardStateSource.collectAsState(EditorWorkspaceViewModel.ClipboardState())

    val hasClipboard by remember {
        derivedStateOf { clipboardState.entries.isNotEmpty() }
    }

    // Derive Ready state properties with safe defaults for non-Ready states
    val readyState = mainState as? EditorWorkspaceViewModel.State.Ready
    val hasActions = readyState?.availableActions?.isNotEmpty() == true

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
                .fillMaxWidth(),
            position = BarPosition.TOP,
            state = topBarStackState,
            bars = {
                // Toolbar - ALWAYS visible with safe defaults for non-Ready states
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    estimatedHeight = 80.dp, // EditorToolbarCard expanded height
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    EditorToolbarCard(
                        workspaceId = workspaceId,
                        design = design,
                        title = readyState?.title ?: "".toCaString(),
                        subTitle = readyState?.subTitle ?: "".toCaString(),
                        isModified = readyState?.isModified ?: false,
                        isLoading = readyState?.isLoading ?: (mainState is EditorWorkspaceViewModel.State.Initializing),
                        hasContent = readyState?.hasContent ?: false,
                        canUndo = readyState?.canUndo ?: false,
                        canRedo = readyState?.canRedo ?: false,
                        onAction = onPageAction,
                        collapsedFraction = collapsedFraction,
                    )
                }
                // InfoBar - only when Ready
                FloatingBar(
                    visible = readyState != null,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    estimatedHeight = 24.dp, // InfoChip height
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    if (readyState != null) {
                        EditorInfoBar(
                            fileSize = readyState.fileSize,
                            totalLines = readyState.totalLines,
                            cursorLine = readyState.cursorPosition.line,
                            cursorColumn = readyState.cursorPosition.column,
                            selectedLineCount = readyState.selectedLineCount,
                            selectedCharacterCount = readyState.selectedCharacterCount,
                            onClearSelection = {
                                onPageAction(EditorPageAction.Navigation.ClearSelection(readyState.cursorPosition))
                            },
                        )
                    }
                }
            },
        )

        // Bottom floating bars - only when Ready
        FloatingBarStack(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            position = BarPosition.BOTTOM,
            state = bottomBarStackState,
            bars = {
                FloatingBar(
                    visible = readyState?.isSearchBarVisible == true,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    if (readyState?.isSearchBarVisible == true) {
                        EditorSearchBar(
                            searchQuery = readyState.searchQueryInput,
                            searchResults = readyState.searchResults,
                            currentIndex = readyState.currentSearchResultIndex,
                            caseSensitive = readyState.searchCaseSensitive,
                            regexEnabled = readyState.searchRegexEnabled,
                            wholeWord = readyState.searchWholeWord,
                            onSearchQueryChange = { onPageAction(EditorPageAction.Search.UpdateQuery(it)) },
                            onCaseSensitiveToggle = { onPageAction(EditorPageAction.Search.ToggleCaseSensitive) },
                            onRegexToggle = { onPageAction(EditorPageAction.Search.ToggleRegex) },
                            onWholeWordToggle = { onPageAction(EditorPageAction.Search.ToggleWholeWord) },
                            onPrevious = { onPageAction(EditorPageAction.Search.PreviousResult) },
                            onNext = { onPageAction(EditorPageAction.Search.NextResult) },
                            onClose = { onPageAction(EditorPageAction.Search.Close) },
                        )
                    }
                }
                FloatingBar(
                    visible = readyState != null && hasClipboard,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    animation = BarAnimation.Bouncy,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    ClipboardBar(
                        workspaceType = Workspace.Type.EDITOR,
                        clipboardEntries = clipboardState.entries,
                        onPasteClick = { onPageAction(EditorPageAction.Clipboard.Paste(it)) },
                        onRemoveClick = { onPageAction(EditorPageAction.Clipboard.Remove(it)) },
                        onEntryClick = { onPageAction(EditorPageAction.Clipboard.ShowInfo(it)) },
                        onClearAll = { onPageAction(EditorPageAction.Clipboard.Clear) },
                    )
                }
                FloatingBar(
                    visible = hasActions,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    if (readyState != null) {
                        EditorActionBar(
                            actions = readyState.availableActions,
                            onActionClick = onActionExecute,
                            onActionLongClick = onActionLongClick,
                        )
                    }
                }
            },
        )

        // Main content area - only rendered when Ready (Init/Error handled by WorkspaceMapper)
        val topContentPadding = topBarStackState.contentPaddingDp()
        val bottomContentPadding = bottomBarStackState.contentPaddingDp()

        val currentState = mainState as? EditorWorkspaceViewModel.State.Ready
        if (currentState != null) {
            EditorReadyContent(
                state = currentState,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                topBarNestedScrollConnection = topBarStackState.nestedScrollConnection,
                bottomBarNestedScrollConnection = bottomBarStackState.nestedScrollConnection,
                onPageAction = onPageAction,
            )
        }
    }

    // Dialogs - only when Ready
    if (readyState?.showGoToLineDialog == true) {
        GoToLineDialog(
            totalLines = readyState.totalLines,
            onGoToLine = { line ->
                onPageAction(EditorPageAction.Navigation.GoToLine(line))
                onPageAction(EditorPageAction.Dialog.DismissGoToLine)
            },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissGoToLine) },
        )
    }

    if (readyState?.showCloseConfirmDialog == true) {
        CloseConfirmDialog(
            onConfirm = { onPageAction(EditorPageAction.Dialog.ConfirmClose) },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissCloseConfirm) },
        )
    }

    clipboardInfoClip?.let { clip ->
        ClipboardInfoBottomSheet(
            clip = clip,
            onDismiss = { onPageAction(EditorPageAction.Clipboard.DismissInfo) },
            onNavigateToSource = null,
            onPaste = { onPageAction(EditorPageAction.Clipboard.Paste(clip)) },
            onRemove = { onPageAction(EditorPageAction.Clipboard.Remove(clip)) },
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


/**
 * Content for Ready state - type-safe, no casting needed.
 */
@Composable
private fun EditorReadyContent(
    state: EditorWorkspaceViewModel.State.Ready,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    topBarNestedScrollConnection: NestedScrollConnection,
    bottomBarNestedScrollConnection: NestedScrollConnection,
    onPageAction: (EditorPageAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Error display (soft errors within Ready state)
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
                    EditorLoadingOverlay()
                } else {
                    // Show editor (with file content or empty in-memory buffer)
                    LazyTextEditor(
                        contentPadding = PaddingValues(
                            top = topContentPadding,
                            bottom = bottomContentPadding,
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
                            onPageAction(EditorPageAction.Navigation.SetCursor(position))
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
                            .nestedScroll(topBarNestedScrollConnection)
                            .nestedScroll(bottomBarNestedScrollConnection),
                    )
                }
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
            mainStateSource = flowOf(
                EditorWorkspaceViewModel.State.Ready(
                    id = Workspace.Id(),
                    title = caString("test.txt"),
                    subTitle = caString("/some/storage/test.txt"),
                    totalLines = 1000,
                    isModified = true,
                    currentContent = "Sample text content\nLine 2\nLine 3",
                )
            ),
            onPageAction = {},
        )
    }
}

