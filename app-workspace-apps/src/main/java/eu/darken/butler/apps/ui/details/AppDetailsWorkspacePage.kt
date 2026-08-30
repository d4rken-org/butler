package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.snapshotFlow
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
import eu.darken.butler.apps.core.details.PackageInfoState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.apps.core.details.components.filter
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.apps.ui.details.components.ComponentsActionBarItem
import eu.darken.butler.apps.ui.details.components.ComponentsSummary
import eu.darken.butler.apps.ui.details.components.appComponentsItems
import eu.darken.butler.apps.ui.details.components.previewComponentsData
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.dragselect.listDragSelect
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.NavigationEventHandler
import androidx.compose.runtime.collectAsState
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.WorkspaceInfoBar
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState
import kotlinx.coroutines.flow.drop

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
    data class SetComponentSelection(val keys: Set<String>) : AppDetailsPageAction
    data class ComponentAction(val item: ComponentsActionBarItem) : AppDetailsPageAction
    data object ClearComponentSelection : AppDetailsPageAction
    data class SetComponentEnabled(val entry: ComponentEntry, val enabled: Boolean) : AppDetailsPageAction
    data object OpenElevatedAccessSetup : AppDetailsPageAction
    data class ForceStop(val app: AppInfo) : AppDetailsPageAction
    data class ClearData(val app: AppInfo) : AppDetailsPageAction
    data object OpenSizeSetup : AppDetailsPageAction
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
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)
    val componentsState by vm.componentsState.collectAsState(initial = ComponentsUiState.Loading)
    val selectedComponentKeys by vm.selectedComponentKeys.collectAsState()
    val componentActions by vm.componentActions.collectAsState()

    state?.let { currentState ->
        AppDetailsWorkspacePage(
            design = design,
            state = currentState,
            componentsState = componentsState,
            selectedComponentKeys = selectedComponentKeys,
            componentActions = componentActions,
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
                    is AppDetailsPageAction.SetComponentSelection -> vm.onComponentSelectionChanged(action.keys)
                    is AppDetailsPageAction.ComponentAction -> vm.onComponentAction(action.item)
                    is AppDetailsPageAction.ClearComponentSelection -> vm.clearComponentSelection()
                    is AppDetailsPageAction.SetComponentEnabled -> vm.onSetComponentEnabled(action.entry, action.enabled)
                    is AppDetailsPageAction.OpenElevatedAccessSetup -> vm.openElevatedAccessSetup()
                    is AppDetailsPageAction.ForceStop -> vm.onForceStop(action.app)
                    is AppDetailsPageAction.ClearData -> vm.onClearData(action.app)
                    is AppDetailsPageAction.OpenSizeSetup -> vm.onOpenSizePermissionSetup()
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
    selectedComponentKeys: Set<String> = emptySet(),
    componentActions: List<ComponentsActionBarItem> = emptyList(),
    workspaceId: Workspace.Id? = null,
    onPageAction: (AppDetailsPageAction) -> Unit = {},
) {
    val appInfo = state.app
    val isModal = state.callerWorkspaceId != null

    val showComponents = state.selectedTab == DetailTab.COMPONENTS
    val showPackageInfo = state.selectedTab == DetailTab.PACKAGE_INFO

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

    // The selection resolves against the controller's unfiltered data while search is page-local, so
    // narrowing the query would otherwise leave a batch action operating on entries the user can no
    // longer see, with a count that disagrees with the visible checkboxes.
    //
    // drop(1) discards the query current when the effect starts, so only edits made within this
    // composition clear. LaunchedEffect(query) would instead fire on every *entry* into composition:
    // a remount (rotation, pane-layout change) would re-run it with the restored, unchanged query
    // and wipe a selection the ViewModel deliberately kept alive across that remount.
    //
    // The state is read inside snapshotFlow rather than via the `query` local: that local is a plain
    // String captured once when this never-restarting effect launched, so observing it would track
    // nothing and the flow would go silent after its first value. For the same reason the block must
    // not gate on `selectedComponentKeys` either — clearing unconditionally is a conflated no-op
    // when nothing is selected, since the controller's keys are a StateFlow.
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery.text.trim() }
            .drop(1)
            .collect { onPageAction(AppDetailsPageAction.ClearComponentSelection) }
    }

    // Single back handler: an active selection clears first, then search closes, then a sub-screen
    // (Components, Package info) returns to Overview, then an Overview shown as a modal closes the
    // workspace. Deliberately unaware of the component sheet — that sheet owns a pane layer of its
    // own, which disables this handler while it is up.
    WorkspaceBackHandler(
        enabled = selectedComponentKeys.isNotEmpty() || showComponents || showPackageInfo || isModal,
    ) {
        when {
            selectedComponentKeys.isNotEmpty() -> onPageAction(AppDetailsPageAction.ClearComponentSelection)

            searchActive -> {
                searchActive = false
                searchQuery = TextFieldValue()
            }

            showComponents || showPackageInfo -> {
                onPageAction(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW))
            }

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

    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        design = design,
    )

    val contentPadding = rememberFloatingBarContentPadding(
        topStackState = topBarStackState,
        bottomStackState = bottomBarStackState,
        start = WorkspacePaddings.ContentHorizontal,
        end = WorkspacePaddings.ContentHorizontal,
    )

    val overviewListState = rememberWorkspaceLazyListState(workspaceId, slot = AppDetailsScrollSlots.OVERVIEW)
    val componentsListState = rememberWorkspaceLazyListState(workspaceId, slot = AppDetailsScrollSlots.COMPONENTS)
    val packageInfoListState = rememberWorkspaceLazyListState(workspaceId, slot = AppDetailsScrollSlots.PACKAGE_INFO)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val activeListState = when (state.selectedTab) {
            DetailTab.COMPONENTS -> componentsListState
            DetailTab.PACKAGE_INFO -> packageInfoListState
            DetailTab.OVERVIEW -> overviewListState
        }
        LazyColumn(
            state = activeListState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topBarStackState.nestedScrollConnection)
                .nestedScroll(bottomBarStackState.nestedScrollConnection)
                .listDragSelect(
                    state = activeListState,
                    // Group headers are not entries, the range simply spans them.
                    orderedKeys = { filteredComponents.all.map { entry -> entry.key } },
                    currentSelection = { selectedComponentKeys },
                    onSelectionChange = { onPageAction(AppDetailsPageAction.SetComponentSelection(it)) },
                    enabled = { showComponents },
                ),
            contentPadding = contentPadding,
            // Cards on the overview are spaced apart; the flat component list stays dense.
            verticalArrangement = Arrangement.spacedBy(if (showComponents) 0.dp else 8.dp),
        ) {
            if (appInfo != null) {
                when (state.selectedTab) {
                    DetailTab.COMPONENTS -> appComponentsItems(
                        state = componentsState,
                        filtered = filteredComponents,
                        query = query,
                        onComponentClick = { onPageAction(AppDetailsPageAction.SelectComponent(it)) },
                        selectedKeys = selectedComponentKeys,
                        // The long press belongs to the drag selection, which owns the whole
                        // gesture; kept non-null so releasing it can't fall through to onClick and
                        // so the press still gives haptic feedback.
                        onComponentLongClick = {},
                    )

                    DetailTab.PACKAGE_INFO -> packageInfoItems(
                        state = state.packageInfo,
                        appInfo = appInfo,
                    )

                    DetailTab.OVERVIEW -> overviewItems(
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
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll,
                    animation = BarAnimation.Slide(),
                    // Re-reveal the toolbar when switching routes so it isn't stuck collapsed.
                    revealOn = state.selectedTab,
                ) {
                    if (showPackageInfo) {
                        AppDetailsToolbarCard(
                            app = state.app,
                            design = design,
                            collapsedFraction = collapsedFraction,
                            subtitle = stringResource(R.string.appdetails_packageinfo_title),
                            onBackClick = { onPageAction(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW)) },
                            backContentDescription = stringResource(R.string.appdetails_back_generic_action),
                            currentWorkspaceId = workspaceId,
                        )
                    } else if (showComponents) {
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

                FloatingBar(
                    key = AppDetailsBarKeys.INFOBAR,
                    visible = showComponents && selectedComponentKeys.isNotEmpty(),
                    scrollBehavior = BarScrollBehavior.Static,
                    animation = BarAnimation.Slide(),
                ) {
                    WorkspaceInfoBar(
                        selectedCount = selectedComponentKeys.size,
                        onClearSelection = { onPageAction(AppDetailsPageAction.ClearComponentSelection) },
                    )
                }
            },
        )

        // Without elevated access the ViewModel hands us an empty list, so this bar never appears —
        // that is the intended "multi-selection offers nothing" behaviour.
        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                FloatingBar(
                    key = AppDetailsBarKeys.ACTIONS,
                    visible = showComponents && componentActions.any { it.isVisible },
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    animation = BarAnimation.Slide(),
                    revealOn = selectedComponentKeys,
                ) {
                    WorkspaceActionBar(
                        actions = componentActions,
                        onActionClick = { action ->
                            onPageAction(AppDetailsPageAction.ComponentAction(action))
                        },
                    )
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
                onClearData = { onPageAction(AppDetailsPageAction.ClearData(appInfo)) },
                canEnableDisable = state.canEnableDisable,
                canForceStop = state.canForceStop,
                canClearData = state.canClearData,
            )
        }
    }

    // Storage Section (full-width row highlight → no content horizontal padding)
    if (state.availablePaths.isNotEmpty() || appInfo.appSize != null || appInfo.cacheSize != null ||
        appInfo.dataSize != null || state.isLoadingSize || !state.sizesAvailable
    ) {
        item {
            DetailSectionCard(
                title = stringResource(R.string.apps_details_section_storage),
                contentHorizontalPadding = 0.dp,
            ) {
                StorageListItems(
                    availablePaths = state.availablePaths,
                    onBrowsePath = { onPageAction(AppDetailsPageAction.BrowsePath(it)) },
                    onOpenSetup = { onPageAction(AppDetailsPageAction.OpenSizeSetup) },
                    app = appInfo,
                    isLoadingSize = state.isLoadingSize,
                    sizesAvailable = state.sizesAvailable,
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

    // Package info Section — same pattern: a summary here, the manifest data on its own screen
    item {
        DetailSectionCard(title = stringResource(R.string.appdetails_packageinfo_title)) {
            PackageInfoSummary(
                appInfo = appInfo,
                onViewAll = { onPageAction(AppDetailsPageAction.NavigateToTab(DetailTab.PACKAGE_INFO)) },
            )
        }
    }
}

@Composable
internal fun DetailSectionCard(
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
private fun AppDetailsWorkspacePageComponentsSelectionPreview() {
    val selected = previewComponentsData.activities
    AppDetailsWorkspacePage(
        design = WorkspaceDesign(),
        state = AppDetailsWorkspace.State(
            app = AppsMockDataProvider.Presets.chrome,
            selectedTab = DetailTab.COMPONENTS,
        ),
        componentsState = ComponentsUiState.Ready(previewComponentsData),
        selectedComponentKeys = selected.map { it.key }.toSet(),
        componentActions = listOf(
            ComponentsActionBarItem.Disable(selected),
            ComponentsActionBarItem.Enable(selected),
        ),
        workspaceId = Workspace.Id(),
        onPageAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspacePagePackageInfoPreview() {
    AppDetailsWorkspacePage(
        design = WorkspaceDesign(),
        state = AppDetailsWorkspace.State(
            app = AppsMockDataProvider.Presets.chrome,
            selectedTab = DetailTab.PACKAGE_INFO,
            packageInfo = PackageInfoState.Ready(previewPackageInfo),
        ),
        workspaceId = Workspace.Id(),
        onPageAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspacePagePackageInfoUnavailablePreview() {
    AppDetailsWorkspacePage(
        design = WorkspaceDesign(),
        state = AppDetailsWorkspace.State(
            app = AppsMockDataProvider.Presets.chrome,
            selectedTab = DetailTab.PACKAGE_INFO,
            packageInfo = PackageInfoState.Unavailable,
        ),
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
