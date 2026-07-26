package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspaceViewModel
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.apps.core.details.components.filter
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.apps.ui.details.components.ComponentsSummary
import eu.darken.butler.apps.ui.details.components.appComponentsItems
import eu.darken.butler.apps.ui.details.components.previewComponentsData
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.NavigationEventHandler
import androidx.compose.runtime.collectAsState
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState

sealed interface AppDetailsPageAction {
    data object Close : AppDetailsPageAction
    data class NavigateToTab(val tab: DetailTab) : AppDetailsPageAction
    data class BrowsePath(val path: APath<*>) : AppDetailsPageAction
    data class LaunchApp(val app: AppInfo) : AppDetailsPageAction
    data class ShowAppInfo(val app: AppInfo) : AppDetailsPageAction
    data class EnableDisable(val app: AppInfo) : AppDetailsPageAction
    data class Uninstall(val app: AppInfo) : AppDetailsPageAction
    data class ExportApk(val app: AppInfo) : AppDetailsPageAction
    data class ShareApk(val app: AppInfo) : AppDetailsPageAction
    data class SelectComponent(val entry: ComponentEntry) : AppDetailsPageAction
    data class ForceStop(val app: AppInfo) : AppDetailsPageAction
    data class ClearCache(val app: AppInfo) : AppDetailsPageAction
    data class ClearData(val app: AppInfo) : AppDetailsPageAction
}

@Composable
fun AppDetailsWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: AppDetailsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppDetailsWorkspaceViewModel.Factory ->
            factory.create(id = id)
        }
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)
    val componentsState by vm.componentsState.collectAsState(initial = ComponentsUiState.Loading)

    state?.let { currentState ->
        AppDetailsWorkspacePage(
            design = design,
            state = currentState,
            componentsState = componentsState,
            workspaceId = id,
            onPageAction = { action ->
                when (action) {
                    is AppDetailsPageAction.Close -> vm.close()
                    is AppDetailsPageAction.NavigateToTab -> vm.onTabSelected(action.tab)
                    is AppDetailsPageAction.BrowsePath -> vm.onBrowsePath(action.path)
                    is AppDetailsPageAction.LaunchApp -> vm.onLaunchApp(action.app)
                    is AppDetailsPageAction.ShowAppInfo -> vm.onShowAppInfo(action.app)
                    is AppDetailsPageAction.EnableDisable -> vm.onEnableDisable(action.app)
                    is AppDetailsPageAction.Uninstall -> vm.onUninstall(action.app)
                    is AppDetailsPageAction.ExportApk -> vm.onExportApk(action.app)
                    is AppDetailsPageAction.ShareApk -> vm.onShareApk(action.app)
                    is AppDetailsPageAction.SelectComponent -> vm.onComponentSelected(action.entry)
                    is AppDetailsPageAction.ForceStop -> vm.onForceStop(action.app)
                    is AppDetailsPageAction.ClearCache -> vm.onClearCache(action.app)
                    is AppDetailsPageAction.ClearData -> vm.onClearData(action.app)
                }
            },
        )
    }
}

@Composable
fun AppDetailsWorkspacePage(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign,
    state: AppDetailsWorkspace.State,
    componentsState: ComponentsUiState = ComponentsUiState.Loading,
    workspaceId: Workspace.Id? = null,
    onPageAction: (AppDetailsPageAction) -> Unit = {},
) {
    val appInfo = state.app
    val isModal = state.callerWorkspaceId != null

    // Only OVERVIEW and COMPONENTS have dedicated UI. PACKAGE_INFO (reachable via legacy persisted
    // sessions) has no screen yet, so it falls back to the overview. Exhaustive on purpose.
    val showComponents = when (state.selectedTab) {
        DetailTab.COMPONENTS -> true
        DetailTab.OVERVIEW -> false
        DetailTab.PACKAGE_INFO -> false
    }

    // Search is page-local: the toolbar lives in this slot, and the query is transient state that
    // must not survive leaving the route.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    LaunchedEffect(showComponents) {
        if (!showComponents) {
            searchActive = false
            searchQuery = TextFieldValue()
        }
    }

    // One normalized query drives filtering, highlighting and the no-matches message, so they can
    // never disagree.
    val query = searchQuery.text.trim()
    val filteredComponents = remember(componentsState, query) {
        (componentsState as? ComponentsUiState.Ready)?.data?.filter(query) ?: ComponentsData()
    }

    // Single back handler: search closes first, then the Components sub-screen returns to Overview,
    // then an Overview shown as a modal closes the workspace. Deliberately unaware of the component
    // sheet — that sheet owns a pane layer of its own, which disables this handler while it is up.
    WorkspaceBackHandler(enabled = showComponents || isModal) {
        when {
            searchActive -> {
                searchActive = false
                searchQuery = TextFieldValue()
            }

            showComponents -> onPageAction(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW))
            else -> onPageAction(AppDetailsPageAction.Close)
        }
    }

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
    )

    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom

    val overviewListState = rememberWorkspaceLazyListState(workspaceId, slot = AppDetailsScrollSlots.OVERVIEW)
    val componentsListState = rememberWorkspaceLazyListState(workspaceId, slot = AppDetailsScrollSlots.COMPONENTS)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        LazyColumn(
            state = if (showComponents) componentsListState else overviewListState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topBarStackState.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topBarStackState.contentPaddingDp(),
                bottom = navBarInset + 16.dp,
                start = WorkspacePaddings.ContentHorizontal,
                end = WorkspacePaddings.ContentHorizontal,
            ),
            // Cards on the overview are spaced apart; the flat component list stays dense.
            verticalArrangement = Arrangement.spacedBy(if (showComponents) 0.dp else 8.dp),
        ) {
            if (appInfo != null) {
                if (showComponents) {
                    appComponentsItems(
                        state = componentsState,
                        filtered = filteredComponents,
                        query = query,
                        onComponentClick = { onPageAction(AppDetailsPageAction.SelectComponent(it)) },
                    )
                } else {
                    overviewItems(
                        appInfo = appInfo,
                        state = state,
                        componentsState = componentsState,
                        onPageAction = onPageAction,
                    )
                }
            }
        }

        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                FloatingBar(
                    key = AppDetailsBarKeys.TOOLBAR,
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    animation = BarAnimation.Slide(),
                    // Re-reveal the toolbar when switching routes so it isn't stuck collapsed.
                    revealOn = showComponents,
                ) {
                    if (showComponents) {
                        AppDetailsToolbarCard(
                            app = state.app,
                            design = design,
                            collapsedFraction = collapsedFraction,
                            subtitle = stringResource(R.string.apps_details_section_components),
                            onBackClick = { onPageAction(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW)) },
                            backContentDescription = stringResource(R.string.appdetails_back_generic_action),
                            currentWorkspaceId = workspaceId,
                            searchActive = searchActive,
                            searchQuery = searchQuery,
                            searchHint = stringResource(R.string.apps_components_search_hint),
                            onSearchQueryChange = { searchQuery = it },
                            onSearchToggle = {
                                searchActive = !searchActive
                                if (!searchActive) {
                                    searchQuery = TextFieldValue()
                                }
                            },
                        )
                    } else {
                        AppDetailsToolbarCard(
                            app = state.app,
                            design = design,
                            collapsedFraction = collapsedFraction,
                            onBackClick = if (isModal) {
                                { onPageAction(AppDetailsPageAction.Close) }
                            } else null,
                            backContentDescription = if (isModal) {
                                stringResource(R.string.appdetails_back_action)
                            } else null,
                            currentWorkspaceId = workspaceId,
                        )
                    }
                }
            },
        )
    }
}

private fun LazyListScope.overviewItems(
    appInfo: AppInfo,
    state: AppDetailsWorkspace.State,
    componentsState: ComponentsUiState,
    onPageAction: (AppDetailsPageAction) -> Unit,
) {
    // Overview Section
    item {
        DetailSectionCard(title = stringResource(R.string.apps_details_section_overview)) {
            AppInformationFields(app = appInfo)
        }
    }

    // Actions Section (full-width row highlight → no content horizontal padding)
    item {
        DetailSectionCard(
            title = stringResource(R.string.apps_details_section_actions),
            contentHorizontalPadding = 0.dp,
        ) {
            ActionsSection(
                app = appInfo,
                onLaunchApp = { onPageAction(AppDetailsPageAction.LaunchApp(appInfo)) },
                onShowAppInfo = { onPageAction(AppDetailsPageAction.ShowAppInfo(appInfo)) },
                onEnableDisable = { onPageAction(AppDetailsPageAction.EnableDisable(appInfo)) },
                onUninstall = { onPageAction(AppDetailsPageAction.Uninstall(appInfo)) },
                onExportApk = { onPageAction(AppDetailsPageAction.ExportApk(appInfo)) },
                onShareApk = { onPageAction(AppDetailsPageAction.ShareApk(appInfo)) },
                onForceStop = { onPageAction(AppDetailsPageAction.ForceStop(appInfo)) },
                onClearCache = { onPageAction(AppDetailsPageAction.ClearCache(appInfo)) },
                onClearData = { onPageAction(AppDetailsPageAction.ClearData(appInfo)) },
                canEnableDisable = state.canEnableDisable,
                canForceStop = state.canForceStop,
                canClearCache = state.canClearCache,
                canClearData = state.canClearData,
            )
        }
    }

    // Storage Section (full-width row highlight → no content horizontal padding)
    if (state.availablePaths.isNotEmpty() || appInfo.appSize != null || appInfo.cacheSize != null || appInfo.dataSize != null) {
        item {
            DetailSectionCard(
                title = stringResource(R.string.apps_details_section_storage),
                contentHorizontalPadding = 0.dp,
            ) {
                StorageListItems(
                    availablePaths = state.availablePaths,
                    onBrowsePath = { onPageAction(AppDetailsPageAction.BrowsePath(it)) },
                    app = appInfo,
                )
            }
        }
    }

    // Components Section — compact summary; full list lives on a dedicated screen
    item {
        DetailSectionCard(title = stringResource(R.string.apps_details_section_components)) {
            ComponentsSummary(
                state = componentsState,
                onViewAll = { onPageAction(AppDetailsPageAction.NavigateToTab(DetailTab.COMPONENTS)) },
            )
        }
    }
}

@Composable
private fun DetailSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    contentHorizontalPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            Column(modifier = Modifier.padding(horizontal = contentHorizontalPadding)) {
                content()
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspacePageComponentsPreview() {
    AppDetailsWorkspacePage(
        design = WorkspaceDesign(),
        state = AppDetailsWorkspace.State(
            app = AppsMockDataProvider.Presets.chrome,
            selectedTab = DetailTab.COMPONENTS,
        ),
        componentsState = ComponentsUiState.Ready(previewComponentsData),
        workspaceId = Workspace.Id(),
        onPageAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspacePagePreview() {
    AppDetailsWorkspacePage(
        design = WorkspaceDesign(),
        state = AppDetailsWorkspace.State(
            app = AppsMockDataProvider.Presets.chrome,
            availablePaths = listOf(
                AppPath(
                    label = "App Data".toCaString(),
                    path = LocalPath.build("/data/data/com.android.chrome"),
                ),
                AppPath(
                    label = "External Storage".toCaString(),
                    path = LocalPath.build("/storage/emulated/0/Android/data/com.android.chrome"),
                ),
                AppPath(
                    label = "Cache".toCaString(),
                    path = LocalPath.build("/data/data/com.android.chrome/cache"),
                ),
            ),
        ),
        onPageAction = {},
    )
}
