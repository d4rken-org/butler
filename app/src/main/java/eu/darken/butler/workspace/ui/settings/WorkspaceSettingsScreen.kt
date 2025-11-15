package eu.darken.butler.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.StayPrimaryLandscape
import androidx.compose.material.icons.twotone.StayPrimaryPortrait
import androidx.compose.material.icons.twotone.SwipeLeft
import androidx.compose.material.icons.twotone.TabletAndroid
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.ui.WorkspacePanelMode

@Composable
fun WorkspaceSettingsScreen(
    state: WorkspaceSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleSwipeGestures: () -> Unit,
    onToggleOnDemandWorkspaceCreation: () -> Unit,
    onToggleLivePreview: () -> Unit,
    onSetLayoutModePortrait: (WorkspacePanelMode) -> Unit,
    onSetLayoutModeLandscape: (WorkspacePanelMode) -> Unit,
) {
    var showPortraitDialog by remember { mutableStateOf(false) }
    var showLandscapeDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workspace_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                eu.darken.butler.common.R.string.general_back_action
                            )
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top
        ) {
            item {
                SettingsCategoryHeader(text = stringResource(R.string.workspace_settings_navigation))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.SwipeLeft,
                    title = stringResource(R.string.workspace_settings_swipe_gestures_title),
                    subtitle = stringResource(R.string.workspace_settings_swipe_gestures_desc),
                    checked = state.swipeGesturesEnabled,
                    onCheckedChange = { onToggleSwipeGestures() }
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.AutoAwesome,
                    title = stringResource(R.string.workspace_settings_ondemand_creation_title),
                    subtitle = stringResource(R.string.workspace_settings_ondemand_creation_desc),
                    checked = state.onDemandWorkspaceCreation,
                    onCheckedChange = { onToggleOnDemandWorkspaceCreation() },
                    enabled = state.swipeGesturesEnabled,
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Visibility,
                    title = stringResource(R.string.workspace_settings_live_preview_title),
                    subtitle = stringResource(R.string.workspace_settings_live_preview_desc),
                    checked = state.livePreview,
                    onCheckedChange = { onToggleLivePreview() }
                )
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.workspace_settings_layout_title))
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.StayPrimaryPortrait,
                    title = stringResource(R.string.workspace_settings_layout_mode_portrait_title),
                    subtitle = stringResource(R.string.workspace_settings_layout_mode_portrait_desc),
                    value = getLayoutModeLabel(state.layoutModePortrait),
                    onClick = { showPortraitDialog = true }
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.StayPrimaryLandscape,
                    title = stringResource(R.string.workspace_settings_layout_mode_landscape_title),
                    subtitle = stringResource(R.string.workspace_settings_layout_mode_landscape_desc),
                    value = getLayoutModeLabel(state.layoutModeLandscape),
                    onClick = { showLandscapeDialog = true }
                )
            }
        }
    }

    if (showPortraitDialog) {
        LayoutModeDialog(
            title = stringResource(R.string.workspace_settings_layout_mode_portrait_title),
            currentMode = state.layoutModePortrait,
            availableModes = listOf(
                WorkspacePanelMode.AUTO,
                WorkspacePanelMode.SINGLE,
                WorkspacePanelMode.DUAL_VERTICAL,
                WorkspacePanelMode.DUAL_HORIZONTAL,
            ),
            onDismiss = { showPortraitDialog = false },
            onConfirm = { mode ->
                onSetLayoutModePortrait(mode)
                showPortraitDialog = false
            }
        )
    }

    if (showLandscapeDialog) {
        LayoutModeDialog(
            title = stringResource(R.string.workspace_settings_layout_mode_landscape_title),
            currentMode = state.layoutModeLandscape,
            availableModes = listOf(
                WorkspacePanelMode.AUTO,
                WorkspacePanelMode.DUAL_HORIZONTAL,
                WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT,
                WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT,
                WorkspacePanelMode.QUAD_GRID,
            ),
            onDismiss = { showLandscapeDialog = false },
            onConfirm = { mode ->
                onSetLayoutModeLandscape(mode)
                showLandscapeDialog = false
            }
        )
    }
}

@Composable
private fun getLayoutModeLabel(mode: WorkspacePanelMode): String {
    return when (mode) {
        WorkspacePanelMode.AUTO -> stringResource(R.string.workspace_settings_layout_mode_auto)
        WorkspacePanelMode.SINGLE -> stringResource(R.string.workspace_settings_layout_mode_single)
        WorkspacePanelMode.DUAL_VERTICAL -> stringResource(R.string.workspace_settings_layout_mode_dual_vertical)
        WorkspacePanelMode.DUAL_HORIZONTAL -> stringResource(R.string.workspace_settings_layout_mode_dual_horizontal)
        WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_left)
        WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_right)
        WorkspacePanelMode.QUAD_GRID -> stringResource(R.string.workspace_settings_layout_mode_quad_grid)
    }
}

@Composable
private fun LayoutModeDialog(
    title: String,
    currentMode: WorkspacePanelMode,
    availableModes: List<WorkspacePanelMode>,
    onDismiss: () -> Unit,
    onConfirm: (WorkspacePanelMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                availableModes.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (mode == currentMode),
                                onClick = { onConfirm(mode) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (mode == currentMode),
                            onClick = null
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = getLayoutModeLabel(mode),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = getLayoutModeDescription(mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun getLayoutModeDescription(mode: WorkspacePanelMode): String {
    return when (mode) {
        WorkspacePanelMode.AUTO -> stringResource(R.string.workspace_settings_layout_mode_auto_desc)
        WorkspacePanelMode.SINGLE -> stringResource(R.string.workspace_settings_layout_mode_single_desc)
        WorkspacePanelMode.DUAL_VERTICAL -> stringResource(R.string.workspace_settings_layout_mode_dual_vertical_desc)
        WorkspacePanelMode.DUAL_HORIZONTAL -> stringResource(R.string.workspace_settings_layout_mode_dual_horizontal_desc)
        WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_left_desc)
        WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_right_desc)
        WorkspacePanelMode.QUAD_GRID -> stringResource(R.string.workspace_settings_layout_mode_quad_grid_desc)
    }
}

@Preview2
@Composable
private fun WorkspaceSettingsScreenPreview() {
    PreviewWrapper {
        WorkspaceSettingsScreen(
            state = WorkspaceSettingsViewModel.State(
                swipeGesturesEnabled = true,
                onDemandWorkspaceCreation = true,
                livePreview = true,
                layoutModePortrait = WorkspacePanelMode.AUTO,
                layoutModeLandscape = WorkspacePanelMode.AUTO,
            ),
            onNavigateUp = {},
            onToggleSwipeGestures = {},
            onToggleOnDemandWorkspaceCreation = {},
            onToggleLivePreview = {},
            onSetLayoutModePortrait = {},
            onSetLayoutModeLandscape = {},
        )
    }
}

@Composable
fun WorkspaceSettingsScreenHost(vm: WorkspaceSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { vmState ->
        WorkspaceSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onToggleSwipeGestures = { vm.toggleSwipeGestures() },
            onToggleOnDemandWorkspaceCreation = { vm.toggleOnDemandWorkspaceCreation() },
            onToggleLivePreview = { vm.toggleLivePreview() },
            onSetLayoutModePortrait = { mode -> vm.setLayoutModePortrait(mode) },
            onSetLayoutModeLandscape = { mode -> vm.setLayoutModeLandscape(mode) },
        )
    }
}