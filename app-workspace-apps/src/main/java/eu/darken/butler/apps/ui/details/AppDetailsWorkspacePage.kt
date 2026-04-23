package eu.darken.butler.apps.ui.details

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspaceViewModel
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.NavigationEventHandler
import androidx.compose.runtime.collectAsState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

sealed interface AppDetailsPageAction {
    data object Close : AppDetailsPageAction
    data class BrowsePath(val path: APath<*>) : AppDetailsPageAction
    data class LaunchApp(val app: AppInfo) : AppDetailsPageAction
    data class ShowAppInfo(val app: AppInfo) : AppDetailsPageAction
    data class EnableDisable(val app: AppInfo) : AppDetailsPageAction
    data class Uninstall(val app: AppInfo) : AppDetailsPageAction
    data class ExportApk(val app: AppInfo) : AppDetailsPageAction
    data class ShareApk(val app: AppInfo) : AppDetailsPageAction
    data class LaunchActivity(val activity: ActivityInfo) : AppDetailsPageAction
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

    state?.let { currentState ->
        AppDetailsWorkspacePage(
            design = design,
            state = currentState,
            workspaceId = id,
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
                    is AppDetailsPageAction.LaunchActivity -> vm.onLaunchActivity(action.activity)
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
    workspaceId: Workspace.Id? = null,
    onPageAction: (AppDetailsPageAction) -> Unit = {},
) {
    val density = LocalDensity.current

    // Only enable back handler in modal mode (when called from another workspace)
    if (state.callerWorkspaceId != null) {
        BackHandler(enabled = true) {
            onPageAction(AppDetailsPageAction.Close)
        }
    }

    val isModal = state.callerWorkspaceId != null

    val topBarStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        includeSystemBarInset = design.paneEdges.touchesTop,
    )

    // Calculate bottom inset based on pane edges
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Main scrollable content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topBarStackState.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topBarStackState.contentPaddingDp(),
                bottom = navBarInset + 16.dp,
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
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }

                // Components Section
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
                                text = stringResource(R.string.apps_details_section_components),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            ComponentsSection(
                                app = appInfo,
                                onLaunchActivity = { activity ->
                                    onPageAction(AppDetailsPageAction.LaunchActivity(activity))
                                },
                            )
                        }
                    }
                }
            }
        }

        // Top floating bars
        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                // Toolbar - collapses on scroll
                FloatingBar(
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    AppDetailsToolbarCard(
                        app = state.app,
                        design = design,
                        isModal = isModal,
                        collapsedFraction = collapsedFraction,
                        onBackClick = { onPageAction(AppDetailsPageAction.Close) },
                        currentWorkspaceId = workspaceId,
                    )
                }
            },
        )
    }
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

