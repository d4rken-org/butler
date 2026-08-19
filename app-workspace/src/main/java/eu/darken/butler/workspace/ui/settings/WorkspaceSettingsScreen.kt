package eu.darken.butler.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.AdsClick
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.RestorePage
import androidx.compose.material.icons.twotone.StayPrimaryLandscape
import androidx.compose.material.icons.twotone.StayPrimaryPortrait
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.SwipeLeft
import androidx.compose.material.icons.twotone.Timer
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.formatAsFileSize
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.MinutesDurationInputDialog
import androidx.compose.runtime.collectAsState
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.layout.description
import eu.darken.butler.workspace.ui.layout.icon
import eu.darken.butler.workspace.ui.layout.label
import eu.darken.butler.workspace.core.WorkspaceSettings
import kotlin.time.Duration
import eu.darken.butler.common.R as CommonR

@Composable
fun WorkspaceSettingsScreen(
    state: WorkspaceSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleSwipeGestures: () -> Unit,
    onToggleOnDemandWorkspaceCreation: () -> Unit,
    onToggleLivePreview: () -> Unit,
    onSetLayoutModePortrait: (WorkspacePanelMode) -> Unit,
    onSetLayoutModeLandscape: (WorkspacePanelMode) -> Unit,
    onTogglePaneClickToFocus: () -> Unit,
    onToggleSessionRestore: () -> Unit,
    onToggleAutoPause: () -> Unit,
    onSetAutoPauseIdleTimeout: (Duration) -> Unit,
) {
    var showPortraitDialog by remember { mutableStateOf(false) }
    var showLandscapeDialog by remember { mutableStateOf(false) }
    var showAutoPauseTimeoutDialog by remember { mutableStateOf(false) }
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
                SettingsCategoryHeader(text = stringResource(R.string.workspace_settings_layout_title))
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.StayPrimaryPortrait,
                    title = stringResource(R.string.workspace_settings_layout_mode_portrait_title),
                    subtitle = stringResource(R.string.workspace_settings_layout_mode_portrait_desc),
                    value = state.layoutModePortrait.label(),
                    onClick = { showPortraitDialog = true }
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.StayPrimaryLandscape,
                    title = stringResource(R.string.workspace_settings_layout_mode_landscape_title),
                    subtitle = stringResource(R.string.workspace_settings_layout_mode_landscape_desc),
                    value = state.layoutModeLandscape.label(),
                    onClick = { showLandscapeDialog = true }
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.AdsClick,
                    title = stringResource(R.string.workspace_settings_pane_click_to_focus_title),
                    subtitle = stringResource(R.string.workspace_settings_pane_click_to_focus_desc),
                    checked = state.paneClickToFocus,
                    onCheckedChange = { onTogglePaneClickToFocus() },
                )
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.workspace_settings_session_title))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.RestorePage,
                    title = stringResource(R.string.workspace_settings_session_restore_title),
                    subtitle = stringResource(R.string.workspace_settings_session_restore_desc),
                    checked = state.sessionRestoreEnabled,
                    onCheckedChange = { onToggleSessionRestore() }
                )
            }

            if (state.sessionRestoreEnabled) {
                item {
                    SettingsPreferenceItem(
                        icon = Icons.TwoTone.Storage,
                        title = stringResource(R.string.workspace_settings_session_data_title),
                        subtitle = pluralStringResource(
                            R.plurals.workspace_settings_session_data_desc,
                            state.sessionWorkspaceCount,
                            state.sessionWorkspaceCount,
                            state.sessionDatabaseSizeBytes.formatAsFileSize(),
                        ),
                        value = null,
                        onClick = {},
                    )
                }
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.PauseCircle,
                    title = stringResource(R.string.workspace_settings_autopause_title),
                    subtitle = stringResource(R.string.workspace_settings_autopause_desc),
                    checked = state.autoPauseEnabled,
                    onCheckedChange = { onToggleAutoPause() },
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Timer,
                    title = stringResource(R.string.workspace_settings_autopause_delay_title),
                    subtitle = stringResource(R.string.workspace_settings_autopause_delay_desc),
                    value = state.autoPauseIdleTimeout.formatCoarse(),
                    onClick = { showAutoPauseTimeoutDialog = true },
                    enabled = state.autoPauseEnabled,
                )
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.workspace_settings_other))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Visibility,
                    title = stringResource(R.string.workspace_settings_live_preview_title),
                    subtitle = stringResource(R.string.workspace_settings_live_preview_desc),
                    checked = state.livePreview,
                    onCheckedChange = { onToggleLivePreview() },
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
                WorkspacePanelMode.SINGLE,
                WorkspacePanelMode.DUAL_VERTICAL,
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

    if (showAutoPauseTimeoutDialog) {
        MinutesDurationInputDialog(
            title = stringResource(R.string.workspace_settings_autopause_delay_title),
            currentDuration = state.autoPauseIdleTimeout,
            minimumDuration = WorkspaceSettings.AUTO_PAUSE_IDLE_TIMEOUT_MIN,
            maximumDuration = WorkspaceSettings.AUTO_PAUSE_IDLE_TIMEOUT_MAX,
            defaultDuration = WorkspaceSettings.AUTO_PAUSE_IDLE_TIMEOUT_DEFAULT,
            onDismiss = { showAutoPauseTimeoutDialog = false },
            onConfirm = { duration ->
                onSetAutoPauseIdleTimeout(duration)
                showAutoPauseTimeoutDialog = false
            },
        )
    }
}

/** "2 hours", "45 minutes", "1 hour 30 minutes" - hours are dropped when zero, and vice versa. */
@Composable
private fun Duration.formatCoarse(): String {
    val hours = inWholeHours.toInt()
    val minutes = (inWholeMinutes - hours * 60L).toInt()
    val hoursText = pluralStringResource(CommonR.plurals.common_duration_hours_full, hours, hours)
    val minutesText = pluralStringResource(CommonR.plurals.common_duration_minutes_full, minutes, minutes)
    return when {
        hours > 0 && minutes > 0 -> "$hoursText $minutesText"
        hours > 0 -> hoursText
        else -> minutesText
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
            Column {
                availableModes.forEach { mode ->
                    val isSelected = mode == currentMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = { onConfirm(mode) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = mode.icon(),
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                            modifier = Modifier.size(32.dp),
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = mode.label(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = mode.description(),
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSettingsScreenPreview() {
    WorkspaceSettingsScreen(
        state = WorkspaceSettingsViewModel.State(
            swipeGesturesEnabled = true,
            onDemandWorkspaceCreation = true,
            livePreview = true,
            layoutModePortrait = WorkspacePanelMode.AUTO,
            layoutModeLandscape = WorkspacePanelMode.AUTO,
            paneClickToFocus = true,
            sessionRestoreEnabled = true,
            sessionWorkspaceCount = 3,
            sessionDatabaseSizeBytes = 131072,
        ),
        onNavigateUp = {},
        onToggleSwipeGestures = {},
        onToggleOnDemandWorkspaceCreation = {},
        onToggleLivePreview = {},
        onSetLayoutModePortrait = {},
        onSetLayoutModeLandscape = {},
        onTogglePaneClickToFocus = {},
        onToggleSessionRestore = {},
        onToggleAutoPause = {},
        onSetAutoPauseIdleTimeout = {},
    )
}

@Composable
fun WorkspaceSettingsScreenHost(vm: WorkspaceSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { vmState ->
        WorkspaceSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onToggleSwipeGestures = { vm.toggleSwipeGestures() },
            onToggleOnDemandWorkspaceCreation = { vm.toggleOnDemandWorkspaceCreation() },
            onToggleLivePreview = { vm.toggleLivePreview() },
            onSetLayoutModePortrait = { mode -> vm.setLayoutModePortrait(mode) },
            onSetLayoutModeLandscape = { mode -> vm.setLayoutModeLandscape(mode) },
            onTogglePaneClickToFocus = { vm.togglePaneClickToFocus() },
            onToggleSessionRestore = { vm.toggleSessionRestore() },
            onToggleAutoPause = { vm.toggleAutoPause() },
            onSetAutoPauseIdleTimeout = { timeout -> vm.setAutoPauseIdleTimeout(timeout) },
        )
    }
}