package eu.darken.butler.apps.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspaceViewModel
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.rememberTopToolbarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeights

sealed interface AppDetailsPageAction {
    data object Close : AppDetailsPageAction
    data class BrowsePath(val path: APath<*>) : AppDetailsPageAction
    data class LaunchApp(val app: AppInfo) : AppDetailsPageAction
    data class ShowAppInfo(val app: AppInfo) : AppDetailsPageAction
    data class EnableDisable(val app: AppInfo) : AppDetailsPageAction
    data class Uninstall(val app: AppInfo) : AppDetailsPageAction
    data class ExportApk(val app: AppInfo) : AppDetailsPageAction
    data class ShareApk(val app: AppInfo) : AppDetailsPageAction
}

@Composable
fun AppDetailsWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: AppDetailsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppDetailsWorkspaceViewModel.Factory ->
            factory.create(id = id, arguments = null)
        }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm, workspaceButtonVm)

    val workspaceButtonState by waitForState(workspaceButtonVm.state)
    val state by waitForState(vm.state)

    state?.let { currentState ->
        AppDetailsWorkspacePage(
            design = design,
            state = currentState,
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceButtonVm,
            onPageAction = { action ->
                when (action) {
                    is AppDetailsPageAction.Close -> vm.close()
                    is AppDetailsPageAction.BrowsePath -> vm.onBrowsePath(action.path)
                    is AppDetailsPageAction.LaunchApp -> vm.onLaunchApp(action.app)
                    is AppDetailsPageAction.ShowAppInfo -> vm.onShowAppInfo(action.app)
                    is AppDetailsPageAction.EnableDisable -> vm.onEnableDisable(action.app)
                    is AppDetailsPageAction.Uninstall -> vm.onUninstall(action.app)
                    is AppDetailsPageAction.ExportApk -> vm.onExportApk(action.app)
                    is AppDetailsPageAction.ShareApk -> vm.onShareApk(action.app)
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
    workspaceButtonState: WorkspaceButtonViewModel.State? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    onPageAction: (AppDetailsPageAction) -> Unit = {},
) {
    val density = LocalDensity.current

    // Only enable back handler in modal mode (when called from another workspace)
    if (state.callerWorkspaceId != null) {
        BackHandler(enabled = true) {
            onPageAction(AppDetailsPageAction.Close)
        }
    }

    // Scroll behavior for toolbar
    val topToolbarScrollBehavior = rememberTopToolbarScrollBehavior()
    var actualToolbarHeightPx by remember { mutableIntStateOf(0) }
    val actualToolbarHeightDp = with(density) { actualToolbarHeightPx.toDp() }

    // Configure top toolbar scroll heights with fixed expected height
    topToolbarScrollBehavior.state.setHeights(
        expandedHeightDp = 88.dp,  // Fixed expected height to prevent negative padding
        collapsedHeightDp = 0.dp
    )

    val isModal = state.callerWorkspaceId != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Main scrollable content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topToolbarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = actualToolbarHeightDp + 8.dp,
                bottom = 16.dp,
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val appInfo = state.app
            if (appInfo != null) {
                // Overview Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 1.dp
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.apps_details_section_overview),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            AppInformationFields(app = appInfo)
                        }
                    }
                }

                // Storage Section
                if (state.availablePaths.isNotEmpty() || appInfo.appSize != null || appInfo.cacheSize != null || appInfo.dataSize != null) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.apps_details_section_storage),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                StorageListItems(
                                    availablePaths = state.availablePaths,
                                    onBrowsePath = { onPageAction(AppDetailsPageAction.BrowsePath(it)) },
                                    app = appInfo,
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }

                // Actions Section
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 1.dp
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                top = 16.dp,
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 8.dp
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.apps_details_section_actions),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            ActionsSection(
                                app = appInfo,
                                onLaunchApp = { onPageAction(AppDetailsPageAction.LaunchApp(appInfo)) },
                                onShowAppInfo = { onPageAction(AppDetailsPageAction.ShowAppInfo(appInfo)) },
                                onEnableDisable = { onPageAction(AppDetailsPageAction.EnableDisable(appInfo)) },
                                onUninstall = { onPageAction(AppDetailsPageAction.Uninstall(appInfo)) },
                                onExportApk = { onPageAction(AppDetailsPageAction.ExportApk(appInfo)) },
                                onShareApk = { onPageAction(AppDetailsPageAction.ShareApk(appInfo)) },
                                onForceStop = { /* TODO: Not implemented yet */ },
                                onClearCache = { /* TODO: Not implemented yet */ },
                                onClearData = { /* TODO: Not implemented yet */ },
                            )
                        }
                    }
                }
            }
        }

        // Floating toolbar card (pinned at top)
        AppDetailsToolbarCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .graphicsLayer {
                    translationY = topToolbarScrollBehavior.state.heightOffset
                    alpha = 1f - topToolbarScrollBehavior.state.collapsedFraction
                }
                .onGloballyPositioned { layoutCoordinates ->
                    actualToolbarHeightPx = layoutCoordinates.size.height
                },
            app = state.app,
            design = design,
            isModal = isModal,
            collapsedFraction = topToolbarScrollBehavior.state.collapsedFraction,
            onBackClick = { onPageAction(AppDetailsPageAction.Close) },
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceActionHandler,
        )
    }
}

@Preview2
@Composable
private fun AppDetailsWorkspacePagePreview() {
    PreviewWrapper {
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
}

