package eu.darken.butler.editor.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.editor.ui.editor.dialogs.CloseConfirmDialog
import eu.darken.butler.editor.ui.editor.dialogs.GoToLineDialog
import eu.darken.butler.editor.ui.editor.elements.EditorActionBar
import eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem
import eu.darken.butler.editor.ui.editor.elements.EditorErrorBanner
import eu.darken.butler.editor.ui.editor.elements.EditorInfoBar
import eu.darken.butler.editor.ui.editor.elements.EditorLoadingOverlay
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
    LifecycleResumeEffect(Unit) {
        vm.refreshClipboardState()
        onPauseOrDispose {}
    }

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
    onActionExecute: (EditorActionBarItem) -> Unit = {},
    onActionLongClick: (EditorActionBarItem) -> Unit = {},
) {
    // Page is hidden by WorkspaceMapper during Init/Error states, so nothing to render until Ready
    val stateOrNull by mainStateSource.collectAsState(null)
    val clipboardState by clipboardStateSource.collectAsState(EditorWorkspaceViewModel.ClipboardState())

    val state = stateOrNull ?: return

    // Handle back button for selection mode - clear selection first
    BackHandler(enabled = state.selectionRange != null) {
        onPageAction(EditorPageAction.Navigation.ClearSelection(state.cursorPosition))
    }

    val hasClipboard by remember { derivedStateOf { clipboardState.entries.isNotEmpty() } }
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Top floating bars
        FloatingBarStack(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            position = BarPosition.TOP,
            state = topBarStackState,
            bars = {
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    estimatedHeight = 80.dp,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    EditorToolbarCard(
                        workspaceId = workspaceId,
                        design = design,
                        title = state.title,
                        subTitle = state.subTitle,
                        isModified = state.isModified,
                        progress = state.progress,
                        hasContent = state.hasContent,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onAction = onPageAction,
                        collapsedFraction = collapsedFraction,
                    )
                }
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    estimatedHeight = 24.dp,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    EditorInfoBar(
                        fileSize = state.fileSize,
                        totalLines = state.totalLines,
                        cursorLine = state.cursorPosition.line,
                        cursorColumn = state.cursorPosition.column,
                        selectedLineCount = state.selectedLineCount,
                        selectedCharacterCount = state.selectedCharacterCount,
                        onClearSelection = { onPageAction(EditorPageAction.Navigation.ClearSelection(state.cursorPosition)) },
                    )
                }
            },
        )

        // Bottom floating bars
        FloatingBarStack(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            position = BarPosition.BOTTOM,
            state = bottomBarStackState,
            bars = {
                FloatingBar(
                    visible = state.isSearchBarVisible,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    EditorSearchBar(
                        searchQuery = state.searchQueryInput,
                        searchResults = state.searchResults,
                        currentIndex = state.currentSearchResultIndex,
                        caseSensitive = state.searchCaseSensitive,
                        regexEnabled = state.searchRegexEnabled,
                        wholeWord = state.searchWholeWord,
                        onSearchQueryChange = { onPageAction(EditorPageAction.Search.UpdateQuery(it)) },
                        onCaseSensitiveToggle = { onPageAction(EditorPageAction.Search.ToggleCaseSensitive) },
                        onRegexToggle = { onPageAction(EditorPageAction.Search.ToggleRegex) },
                        onWholeWordToggle = { onPageAction(EditorPageAction.Search.ToggleWholeWord) },
                        onPrevious = { onPageAction(EditorPageAction.Search.PreviousResult) },
                        onNext = { onPageAction(EditorPageAction.Search.NextResult) },
                        onClose = { onPageAction(EditorPageAction.Search.Close) },
                    )
                }
                FloatingBar(
                    visible = hasClipboard,
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
                    EditorActionBar(
                        actions = state.availableActions,
                        onActionClick = onActionExecute,
                        onActionLongClick = onActionLongClick,
                    )
                }
            },
        )

        // Main content area
        val topContentPadding = topBarStackState.contentPaddingDp()
        val bottomContentPadding = bottomBarStackState.contentPaddingDp()

        Column(modifier = Modifier.fillMaxSize()) {
            // Error banner (soft errors within Ready state)
            state.error?.let { error ->
                EditorErrorBanner(
                    error = error,
                    onDismiss = { onPageAction(EditorPageAction.Error.Clear) },
                )
            }

            // Main editor content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (!state.hasFile && state.isLoading) {
                    EditorLoadingOverlay()
                } else {
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
                        onForwardDelete = { onPageAction(EditorPageAction.Edit.ForwardDelete) },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(topBarStackState.nestedScrollConnection)
                            .nestedScroll(bottomBarStackState.nestedScrollConnection),
                    )
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
                onPageAction(EditorPageAction.Dialog.DismissGoToLine)
            },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissGoToLine) },
        )
    }

    if (state.showCloseConfirmDialog) {
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

@Preview2
@Composable
private fun EditorPagePreview() {
    PreviewWrapper {
        EditorWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            mainStateSource = flowOf(
                EditorWorkspaceViewModel.State(
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

