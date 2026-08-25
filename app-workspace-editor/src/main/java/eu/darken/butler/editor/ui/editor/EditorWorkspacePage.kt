package eu.darken.butler.editor.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.OnValueChange
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.editor.ui.editor.elements.EditorActionBar
import eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem
import eu.darken.butler.editor.ui.editor.elements.EditorBannerGroup
import eu.darken.butler.editor.ui.editor.elements.EditorInfoBar
import eu.darken.butler.editor.ui.editor.elements.EditorLoadingOverlay
import eu.darken.butler.editor.ui.editor.elements.EditorSearchBar
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.ui.editor.elements.EditorToolbarCard
import eu.darken.butler.editor.ui.editor.text.LazyTextEditor
import eu.darken.butler.editor.ui.editor.text.SessionDelta
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration.Companion.seconds

private val EXTERNAL_CHANGE_POLL_INTERVAL = 15.seconds

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
    NavigationEventHandler(vm)
    LifecycleResumeEffect(Unit) {
        vm.refreshClipboardState()
        onPauseOrDispose {}
    }
    // External-change polling only runs while this page is resumed; background tabs stay quiet
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.checkExternalChange()
                delay(EXTERNAL_CHANGE_POLL_INTERVAL)
            }
        }
    }

    EditorWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        onPageAction = vm::onPageAction,
        // Direct reference, not an EditorPageAction: the field chains its next keystroke on this
        // edit's acknowledgement, so the call has to hand back a Deferred.
        onEnqueueDelta = vm::enqueueFieldDelta,
        onActionExecute = vm::executeAction,
    )
}

@Composable
fun EditorWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    mainStateSource: Flow<EditorWorkspaceViewModel.State>,
    clipboardStateSource: Flow<ClipboardDisplayState> = flowOf(ClipboardDisplayState()),
    onPageAction: (EditorPageAction) -> Unit,
    onEnqueueDelta: (SessionDelta) -> Deferred<EditorEngine.MutationResult> = {
        CompletableDeferred(EditorEngine.MutationResult.Failed(IllegalStateException("No editor attached")))
    },
    onActionExecute: (EditorActionBarItem) -> Unit = {},
) {
    // Page is hidden by WorkspaceMapper during Init/Error states, so nothing to render until Ready
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val stateOrNull by mainStateSource.collectAsState(initial = (mainStateSource as? StateFlow)?.value)
    val clipboardState by clipboardStateSource.collectAsState(ClipboardDisplayState())

    val state = stateOrNull ?: return

    // Handle back button for selection mode - clear selection first
    WorkspaceBackHandler(enabled = state.selectionRange != null) {
        onPageAction(EditorPageAction.Navigation.ClearSelection(state.cursorPosition))
    }

    val hasClipboard by remember { derivedStateOf { clipboardState.entries.isNotEmpty() } }
    val hasActions = state.availableActions.isNotEmpty()

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 184.dp,
    )
    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        design = design,
        // Editor bars (in-document search) and content must rise above the soft keyboard.
        includeImeInset = true,
        estimatedContentPadding = 80.dp,
    )

    // Opening a different file is fresh content; reset scroll-collapse so bars don't stay hidden.
    // Guarded on the transition, and keyed on the claimed path rather than the content source -
    // see editorBarResetIdentity for why neither the initial composition nor the source settling
    // may reset (both would undo the collapse state this workspace just restored).
    OnValueChange(editorBarResetIdentity(state)) { _, _ ->
        topBarStackState.resetScrollCollapse()
        bottomBarStackState.resetScrollCollapse()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Stacked banners must never crowd out the editor in short (landscape) viewports; the
        // group scrolls internally past this cap and the stack's measured height keeps the
        // content padding in sync automatically
        val bannerMaxHeight = maxHeight * 0.35f

        // Top floating bars
        FloatingBarStack(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.TopCenter),
            position = BarPosition.TOP,
            state = topBarStackState,
            bars = {
                FloatingBar(
                    key = EditorBarKeys.TOOLBAR,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll,
                    estimatedHeight = 80.dp,
                    animation = BarAnimation.Slide(),
                ) {
                    EditorToolbarCard(
                        workspaceId = workspaceId,
                        design = design,
                        title = state.title,
                        subTitle = state.subTitle,
                        isModified = state.isModified,
                        isReadOnly = state.isReadOnly,
                        isBackingLost = state.isBackingLost,
                        progress = state.progress,
                        hasContent = state.hasContent,
                        hasFile = state.hasFile,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onAction = onPageAction,
                        collapsedFraction = collapsedFraction,
                    )
                }
                FloatingBar(
                    key = EditorBarKeys.INFOBAR,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    estimatedHeight = 24.dp,
                    animation = BarAnimation.Slide(),
                ) {
                    EditorInfoBar(
                        fileSize = state.fileSize,
                        totalLines = state.totalLines,
                        cursorLine = state.cursorPosition.line,
                        cursorColumn = state.cursorPosition.column,
                        selectedLineCount = state.selectedLineCount,
                        selectedCharacterCount = state.selectedCharacterCount,
                        fileEncoding = if (state.hasFile) state.fileEncoding else null,
                        lineEnding = state.lineEnding,
                        isReadOnly = state.isReadOnly,
                        onEncodingClick = { onPageAction(EditorPageAction.File.ShowEncodingPicker) },
                        onLineEndingClick = if (state.isReadOnly) {
                            null // conversion writes the file; read-only/binary documents can't
                        } else {
                            { onPageAction(EditorPageAction.File.ShowLineEndingPicker) }
                        },
                        onClearSelection = { onPageAction(EditorPageAction.Navigation.ClearSelection(state.cursorPosition)) },
                    )
                }
                // Notices persist during scroll (Static) until dismissed; single stable bar, see EditorBannerGroup
                FloatingBar(
                    key = EditorBarKeys.BANNERS,
                    visible = state.isBackingLost || state.error != null || state.showExternalChangeBanner ||
                        state.showBackupNotice || state.isBinary || state.showLongLinesNotice,
                    scrollBehavior = BarScrollBehavior.Static,
                    estimatedHeight = 56.dp,
                    animation = BarAnimation.Slide(),
                ) {
                    EditorBannerGroup(
                        modifier = Modifier.heightIn(max = bannerMaxHeight),
                        // The backing-lost banner already explains the vanished file; the raw
                        // read error underneath it would just be a cryptic duplicate.
                        error = state.error.takeUnless { state.isBackingLost },
                        showBackingLost = state.isBackingLost,
                        showExternalChange = state.showExternalChangeBanner,
                        backupNames = state.staleBackups.map { it.name },
                        showBackupNotice = state.showBackupNotice,
                        isBinary = state.isBinary,
                        showLongLinesNotice = state.showLongLinesNotice,
                        onDismissError = { onPageAction(EditorPageAction.Error.Clear) },
                        onCloseBackingLost = { onPageAction(EditorPageAction.Workspace.Close) },
                        onReloadFromDisk = { onPageAction(EditorPageAction.File.ReloadFromDisk) },
                        onDismissExternalChange = { onPageAction(EditorPageAction.File.DismissExternalChange) },
                        onDismissBackupNotice = { onPageAction(EditorPageAction.File.DismissBackupNotice) },
                        onDismissLongLinesNotice = { onPageAction(EditorPageAction.File.DismissLongLinesNotice) },
                    )
                }
            },
        )

        // Bottom floating bars
        FloatingBarStack(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.BottomCenter),
            position = BarPosition.BOTTOM,
            state = bottomBarStackState,
            bars = {
                FloatingBar(
                    key = EditorBarKeys.SEARCH,
                    visible = state.isSearchBarVisible,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                ) {
                    EditorSearchBar(
                        visible = state.isSearchBarVisible,
                        searchQuery = state.searchQueryInput,
                        searchResults = state.searchResults,
                        searchTruncated = state.searchTruncated,
                        currentIndex = state.currentSearchResultIndex,
                        caseSensitive = state.searchCaseSensitive,
                        regexEnabled = state.searchRegexEnabled,
                        wholeWord = state.searchWholeWord,
                        replaceQuery = state.replaceQueryInput,
                        showReplaceRow = state.showReplaceRow,
                        replaceAllowed = !state.isReadOnly,
                        replaceNotice = state.replaceNotice,
                        onSearchQueryChange = { onPageAction(EditorPageAction.Search.UpdateQuery(it)) },
                        onCaseSensitiveToggle = { onPageAction(EditorPageAction.Search.ToggleCaseSensitive) },
                        onRegexToggle = { onPageAction(EditorPageAction.Search.ToggleRegex) },
                        onWholeWordToggle = { onPageAction(EditorPageAction.Search.ToggleWholeWord) },
                        onPrevious = { onPageAction(EditorPageAction.Search.PreviousResult) },
                        onNext = { onPageAction(EditorPageAction.Search.NextResult) },
                        onClose = { onPageAction(EditorPageAction.Search.Close) },
                        onToggleReplaceRow = { onPageAction(EditorPageAction.Search.ToggleReplaceRow) },
                        onReplaceQueryChange = { onPageAction(EditorPageAction.Search.UpdateReplaceQuery(it)) },
                        onReplaceCurrent = { onPageAction(EditorPageAction.Search.ReplaceCurrent) },
                        onReplaceAll = { onPageAction(EditorPageAction.Search.ReplaceAll) },
                    )
                }
                FloatingBar(
                    key = EditorBarKeys.CLIPBOARD,
                    visible = hasClipboard,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    animation = BarAnimation.Bouncy,
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
                    key = EditorBarKeys.ACTIONS,
                    visible = hasActions,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    revealOn = state.selectionRange,
                ) {
                    EditorActionBar(
                        actions = state.availableActions,
                        onActionClick = onActionExecute,
                    )
                }
            },
        )

        // Main content area
        val contentPadding = rememberFloatingBarContentPadding(
            topStackState = topBarStackState,
            bottomStackState = bottomBarStackState,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.hasFile && state.isLoading) {
                EditorLoadingOverlay()
            } else {
                LazyTextEditor(
                    contentPadding = contentPadding,
                    content = state.currentContent,
                    totalLines = state.totalLines,
                    cursorPosition = state.cursorPosition,
                    selection = state.selectionRange,
                    visibleRange = state.visibleRange,
                    truncatedLines = state.truncatedLines,
                    startColumns = state.startColumns,
                    windowToken = state.windowToken,
                    windowRangeStart = state.windowRangeStart,
                    highlightedLines = state.highlightedLines,
                    showLineNumbers = state.showLineNumbers,
                    wordWrap = state.wordWrap,
                    readOnly = state.isReadOnly,
                    fontSize = state.fontSize,
                    tabSize = state.tabSize,
                    searchResults = state.searchResults,
                    currentSearchResultIndex = state.currentSearchResultIndex,
                    scrollTrigger = state.scrollTrigger,
                    onEnqueueDelta = onEnqueueDelta,
                    onCursorPositionChange = { position ->
                        onPageAction(EditorPageAction.Navigation.SetCursor(position))
                    },
                    onSelectionChange = { selection ->
                        if (selection != null) {
                            onPageAction(EditorPageAction.Navigation.SetSelection(selection.first, selection.second))
                        } else {
                            onPageAction(EditorPageAction.Navigation.ClearSelection(state.cursorPosition))
                        }
                    },
                    onVisibleRangeChange = { range ->
                        onPageAction(EditorPageAction.Navigation.UpdateVisibleRange(range.first, range.last))
                    },
                    onRevealMoreColumns = { forward ->
                        onPageAction(EditorPageAction.Navigation.RevealMoreColumns(forward))
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

    // Dialogs and sheets live in the page host's overlay slot, see EditorWorkspaceOverlays
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorPagePreview() {
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

