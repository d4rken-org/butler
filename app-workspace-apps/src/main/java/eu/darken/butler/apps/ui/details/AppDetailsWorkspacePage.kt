package eu.darken.butler.apps.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspaceViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.getCurrentHeightDp
import eu.darken.butler.workspace.ui.scroll.rememberBottomBarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.rememberTopToolbarScrollBehavior
import eu.darken.butler.workspace.ui.scroll.setHeight
import eu.darken.butler.workspace.ui.scroll.setHeights

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
    val density = LocalDensity.current

    // Only enable back handler in modal mode (when called from another workspace)
    if (state.callerWorkspaceId != null) {
        BackHandler(enabled = true) {
            vm.close()
        }
    }

    // Scroll behavior for toolbar and action bar
    val topToolbarScrollBehavior = rememberTopToolbarScrollBehavior()
    val bottomBarScrollBehavior = rememberBottomBarScrollBehavior()
    var toolbarHeightPx by remember { mutableStateOf(0) }

    val actionBarHeightDp = 48.dp
    bottomBarScrollBehavior.state.setHeight(actionBarHeightDp)

    // Configure top toolbar scroll heights after measurement
    topToolbarScrollBehavior.state.setHeights(
        expandedHeightDp = with(density) { toolbarHeightPx.toDp() },
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
                .nestedScroll(topToolbarScrollBehavior.nestedScrollConnection)
                .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topToolbarScrollBehavior.state.getCurrentHeightDp() + 8.dp,
                bottom = actionBarHeightDp + 8.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.app != null) {
                // Quick Actions Section
                item {
                    SectionHeader(title = stringResource(R.string.apps_details_section_quick_actions))
                }
                item {
                    QuickActionsBar(
                        app = state.app,
                        onLaunchApp = { vm.onLaunchApp(state.app!!) },
                        onShowAppInfo = { vm.onShowAppInfo(state.app!!) },
                        onEnableDisable = { vm.onEnableDisable(state.app!!) },
                        onUninstall = { vm.onUninstall(state.app!!) },
                        onExportApk = { vm.onExportApk(state.app!!) },
                        onShareApk = { vm.onShareApk(state.app!!) },
                    )
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Overview Section
                item {
                    SectionHeader(title = stringResource(R.string.apps_details_section_overview))
                }
                item {
                    AppInformationFields(app = state.app)
                }

                // Storage Section
                if (state.availablePaths.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                    item {
                        SectionHeader(title = stringResource(R.string.apps_details_section_storage))
                    }
                    item {
                        StorageListItems(
                            availablePaths = state.availablePaths,
                            onBrowsePath = { vm.onBrowsePath(it) },
                        )
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
                    toolbarHeightPx = layoutCoordinates.size.height
                },
            app = state.app,
            design = design,
            isModal = isModal,
            collapsedFraction = topToolbarScrollBehavior.state.collapsedFraction,
            onBackClick = { vm.close() },
            workspaceButtonState = workspaceButtonState,
            workspaceActionHandler = workspaceActionHandler,
        )

        // Floating action bar (pinned at bottom)
        AnimatedVisibility(
            visible = state.app != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                initialOffsetY = { it }
            ),
            exit = slideOutVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                targetOffsetY = { it }
            )
        ) {
            AppDetailsActionBar(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .graphicsLayer {
                        translationY = -bottomBarScrollBehavior.state.heightOffset
                    },
                onExportApk = { state.app?.let { vm.onExportApk(it) } },
                onMoreOptions = { /* TODO: Implement more options menu */ },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            letterSpacing = 0.5.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp)
    )
}
