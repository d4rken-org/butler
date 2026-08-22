package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem
import eu.darken.butler.apps.ui.apps.elements.AppsInfoBar
import eu.darken.butler.apps.ui.apps.elements.AppsReadyContent
import eu.darken.butler.apps.ui.apps.elements.AppsToolbarCard
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyGridState
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

@Composable
fun AppsWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<AppsWorkspaceViewModel.State>,
    onPageAction: (AppsPageAction) -> Unit = {},
) {
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val mainStateRaw by stateSource.collectAsState(
        initial = (stateSource as? StateFlow)?.value ?: AppsWorkspaceViewModel.State.Initializing
    )

    // Only render when Ready - WorkspaceMapper handles Init/Error overlays
    val state = mainStateRaw as? AppsWorkspaceViewModel.State.Ready ?: return

    WorkspaceBackHandler(enabled = state.isMultiSelectMode) {
        onPageAction(AppsPageAction.Selection.Clear)
    }

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 156.dp,
    )

    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        design = design,
        estimatedContentPadding = 80.dp,
    )

    val hasActions by remember(state.availableActions) {
        derivedStateOf { state.availableActions.isNotEmpty() }
    }

    val showInfoBar by remember(state.apps, state.selectionCount) {
        derivedStateOf { state.apps.isNotEmpty() || state.selectionCount > 0 }
    }

    val listState = rememberWorkspaceLazyListState(workspaceId, slot = AppsScrollSlots.LIST)
    val gridState = rememberWorkspaceLazyGridState(workspaceId, slot = AppsScrollSlots.GRID)

    Box(modifier = Modifier.fillMaxSize()) {
        AppsReadyContent(
            state = state,
            listState = listState,
            gridState = gridState,
            topBarStackState = topBarStackState,
            bottomBarStackState = bottomBarStackState,
            onPageAction = onPageAction,
        )

        // Top floating bars
        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                // Toolbar card - collapses on scroll
                FloatingBar(
                    key = AppsBarKeys.TOOLBAR,
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll,
                    animation = BarAnimation.Slide(),
                ) {
                    AppsToolbarCard(
                        workspaceId = workspaceId,
                        searchQuery = state.searchQuery,
                        onSearchQueryChange = { onPageAction(AppsPageAction.Search.UpdateQuery(it)) },
                        filterConfig = state.filterConfig,
                        onFilterAdd = { onPageAction(AppsPageAction.Filter.OpenDialog) },
                        onFilterRemove = { tag, isExcluded ->
                            onPageAction(AppsPageAction.Filter.RemoveTag(tag, isExcluded))
                        },
                        design = design,
                        collapsedFraction = collapsedFraction,
                    )
                }

                // Info bar - vanishes on scroll
                FloatingBar(
                    key = AppsBarKeys.INFOBAR,
                    visible = showInfoBar,
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    animation = BarAnimation.Slide(),
                ) {
                    AppsInfoBar(
                        userAppsCount = state.userAppsCount,
                        systemAppsCount = state.systemAppsCount,
                        selectedCount = state.selectionCount,
                        onClearSelection = { onPageAction(AppsPageAction.Selection.Clear) },
                        onSelectUserApps = { onPageAction(AppsPageAction.Selection.SelectUserApps) },
                        onSelectSystemApps = { onPageAction(AppsPageAction.Selection.SelectSystemApps) },
                    )
                }
            },
        )

        // Bottom floating bars
        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                FloatingBar(
                    key = AppsBarKeys.ACTIONS,
                    visible = hasActions,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    revealOn = state.selectedAppIds,
                ) {
                    WorkspaceActionBar(
                        actions = state.availableActions,
                        onActionClick = { action ->
                            onPageAction(AppsPageAction.ActionBarClick(action as AppsActionBarItem))
                        },
                    )
                }
            },
        )
    }

    // Dialogs and sheets live in the page host's overlay slot, see AppsWorkspaceOverlays
}


@Composable
fun AppsWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: AppsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppsWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    NavigationEventHandler(vm)

    AppsWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        onPageAction = vm::onPageAction,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspacePagePreview() {
    val mockApps = listOf(
        AppsMockDataProvider.Presets.chromeItem,
        AppsMockDataProvider.Presets.settingsItem,
        AppsMockDataProvider.Presets.notesItem,
        AppsMockDataProvider.Presets.systemUiItem,
        AppsMockDataProvider.Presets.disabledAppItem,
    )

    val mockState = AppsWorkspaceViewModel.State.Ready(
        apps = mockApps,
        availableActions = listOf(
            AppsActionBarItem.Refresh,
            AppsActionBarItem.Sort,
        ),
        isLoading = false,
    )

    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(mockState),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspacePageEmptyPreview() {
    val mockState = AppsWorkspaceViewModel.State.Ready(
        apps = emptyList(),
        availableActions = listOf(
            AppsActionBarItem.Refresh,
            AppsActionBarItem.Sort,
        ),
        isLoading = false,
    )

    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(mockState),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspacePageWithSelectionPreview() {
    val mockApps = listOf(
        AppsMockDataProvider.Presets.chromeItem,
        AppsMockDataProvider.Presets.settingsItem,
        AppsMockDataProvider.Presets.notesItem,
        AppsMockDataProvider.Presets.disabledAppItem,
    )

    val mockState = AppsWorkspaceViewModel.State.Ready(
        apps = mockApps,
        selectedAppIds = setOf(
            AppsMockDataProvider.Presets.chromeItem.pkg.installId,
            AppsMockDataProvider.Presets.notesItem.pkg.installId,
        ),
        availableActions = listOf(
            AppsActionBarItem.DeselectAll,
            AppsActionBarItem.SelectAll,
            AppsActionBarItem.Disable(mockApps.take(2)),
            AppsActionBarItem.Uninstall(mockApps.take(2)),
        ),
        isLoading = false,
    )

    PreviewWrapper {
        AppsWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            stateSource = flowOf(mockState),
        )
    }
}
