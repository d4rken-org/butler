package eu.darken.butler.appdetails.ui

import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.darken.butler.appdetails.R
import eu.darken.butler.appdetails.core.AppDetailsWorkspace
import eu.darken.butler.appdetails.core.AppDetailsWorkspaceViewModel
import eu.darken.butler.appdetails.core.DetailTab
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

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
    workspaceButtonVm: eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(
        initial = eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel.State()
    )

    AppDetailsWorkspacePage(
        vm = vm,
        design = design,
        workspaceButtonState = workspaceButtonState,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Composable
fun AppDetailsWorkspacePage(
    vm: AppDetailsWorkspaceViewModel?,
    design: WorkspaceDesign,
    workspaceButtonState: eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel.State? = null,
    workspaceActionHandler: eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler? = null,
    modifier: Modifier = Modifier,
) {
    val state by vm!!.state.collectAsState(
        initial = AppDetailsWorkspace.State()
    )

    val context = LocalContext.current

    // Only enable back handler in modal mode (when called from another workspace)
    if (state.callerWorkspaceId != null) {
        BackHandler(enabled = true) {
            vm.close()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (state.callerWorkspaceId != null) {
                // Modal mode - show back arrow
                AppDetailsModalTopBar(
                    title = state.app?.label?.get(context) ?: "",
                    onBackClick = { vm.close() },
                )
            } else {
                // Tab mode - show workspace button (if single pane)
                AppDetailsTopBar(
                    title = state.app?.label?.get(context) ?: "",
                    showWorkspaceButton = design.isSingle,
                    workspaceButtonState = workspaceButtonState,
                    workspaceActionHandler = workspaceActionHandler,
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (state.app != null) {
                AppDetailsTabRow(
                    selectedTab = state.selectedTab,
                    onTabSelected = { vm.onTabSelected(it) },
                )

                when (state.selectedTab) {
                    DetailTab.OVERVIEW -> OverviewTab(
                        state = state,
                        vm = vm,
                    )

                    DetailTab.PACKAGE_INFO -> PackageInfoTab(
                        state = state,
                        vm = vm,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailsModalTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
                    contentDescription = stringResource(R.string.appdetails_back_action),
                )
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailsTopBar(
    title: String,
    showWorkspaceButton: Boolean,
    workspaceButtonState: eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel.State?,
    workspaceActionHandler: eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler?,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = { Text(text = title) },
        actions = {
            if (showWorkspaceButton && workspaceButtonState != null && workspaceActionHandler != null) {
                eu.darken.butler.workspace.ui.manager.WorkspaceButton(
                    state = workspaceButtonState,
                    workspaceActionHandler = workspaceActionHandler,
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun AppDetailsTabRow(
    selectedTab: DetailTab,
    onTabSelected: (DetailTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        modifier = modifier.fillMaxWidth(),
    ) {
        DetailTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = {
                    Text(
                        text = when (tab) {
                            DetailTab.OVERVIEW -> stringResource(R.string.appdetails_tab_overview_label)
                            DetailTab.PACKAGE_INFO -> stringResource(R.string.appdetails_tab_packageinfo_label)
                        }
                    )
                }
            )
        }
    }
}
