package eu.darken.butler.apps.ui.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.twotone.FilterAlt
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.apps.R
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogHost
import eu.darken.butler.apps.ui.apps.items.AppListItem
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun AppsWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: AppsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppsWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    AppsWorkspacePage(
        design = design,
        vm = vm,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsWorkspacePage(
    design: WorkspaceDesign,
    vm: AppsWorkspaceViewModel,
) {
    val state by vm.state.collectAsState(
        initial = AppsWorkspaceViewModel.State()
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val hasActions by remember {
        derivedStateOf { state.availableActions.isNotEmpty() }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apps_title)) },
                actions = {
                    IconButton(onClick = { vm.showFilterDialog() }) {
                        Icon(
                            imageVector = Icons.TwoTone.FilterAlt,
                            contentDescription = "Filter apps"
                        )
                    }
                    IconButton(onClick = { vm.showSortDialog() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.Sort,
                            contentDescription = "Sort apps"
                        )
                    }
                    IconButton(onClick = { vm.onRefresh() }) {
                        Icon(
                            imageVector = Icons.TwoTone.Refresh,
                            contentDescription = stringResource(R.string.apps_action_refresh)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { vm.onRefresh() },
                modifier = Modifier.padding(paddingValues),
            ) {
                when {
                    state.isLoading && state.apps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.apps_empty_loading))
                            }
                        }
                    }

                    state.apps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.apps_empty_no_apps))
                                Text(stringResource(R.string.apps_empty_no_apps_desc))
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = if (hasActions) 72.dp else 8.dp, // Extra padding for action bar
                            ),
                        ) {
                            items(
                                items = state.apps,
                                key = { it.packageName }
                            ) { appItem ->
                                AppListItem(
                                    item = appItem,
                                    isSelected = appItem.packageName in state.selectedAppIds,
                                    onClick = {
                                        if (state.isMultiSelectMode) {
                                            vm.onAppLongClick(appItem)
                                        } else {
                                            vm.showAppDetails(appItem)
                                        }
                                    },
                                    onLongClick = { vm.onAppLongClick(appItem) },
                                    onInfoClick = { vm.openAppInfo(appItem.id) },
                                )
                            }
                        }
                    }
                }
            }

            // Floating Bottom ActionBar - Selection mode
            AnimatedVisibility(
                visible = hasActions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                WorkspaceActionBar(
                    actions = state.availableActions,
                    onActionClick = { action ->
                        when (val appsAction = action as AppsAction) {
                            is AppsAction.DeselectAll -> vm.onClearSelection()
                            else -> vm.onAction(appsAction)
                        }
                    },
                    selectionCount = if (state.isMultiSelectMode) state.selectionCount else null,
                )
            }
        }

        // Dialog Host
        AppsDialogHost(
            dialogState = state.dialogState,
            onDismiss = { vm.dismissDialog() },
            onAction = { action -> vm.onAction(action) },
            onFilterApply = { filter -> vm.onFilterChanged(filter) },
            onSortApply = { sortMode -> vm.onSortModeChanged(sortMode) },
        )
    }
}
