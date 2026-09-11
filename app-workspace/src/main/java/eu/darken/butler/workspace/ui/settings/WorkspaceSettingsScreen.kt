package eu.darken.butler.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material.icons.twotone.AdsClick
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.RestorePage
import androidx.compose.material.icons.twotone.StayPrimaryLandscape
import androidx.compose.material.icons.twotone.StayPrimaryPortrait
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.SwipeLeft
import androidx.compose.material.icons.twotone.SettingsBackupRestore
import androidx.compose.material.icons.twotone.Timer
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
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
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.layout.LayoutPickerDialog
import eu.darken.butler.workspace.ui.layout.LayoutPickerOption
import eu.darken.butler.workspace.ui.layout.WorkspaceLayoutSurface
import eu.darken.butler.workspace.ui.layout.description
import eu.darken.butler.workspace.ui.layout.icon
import eu.darken.butler.workspace.ui.layout.label
import eu.darken.butler.workspace.ui.layout.surface
import eu.darken.butler.workspace.ui.layout.surfaceValueLabel
import eu.darken.butler.workspace.ui.layout.toPanelMode
import eu.darken.butler.workspace.core.WorkspaceSettings
import kotlin.time.Duration
import eu.darken.butler.common.R as CommonR

@Composable
fun WorkspaceSettingsScreen(
    state: WorkspaceSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleSwipeGestures: () -> Unit,
    onToggleOnDemandWorkspaceCreation: () -> Unit,
    onSetDefaultNewTabType: (Workspace.Type) -> Unit,
    onToggleLivePreview: () -> Unit,
    onSetLayoutModePortrait: (WorkspacePanelMode) -> Unit,
    onSetLayoutModeLandscape: (WorkspacePanelMode) -> Unit,
    onTogglePaneClickToFocus: () -> Unit,
    onToggleSessionRestore: () -> Unit,
    onToggleAutoPause: () -> Unit,
    onSetAutoPauseIdleTimeout: (Duration) -> Unit,
    onToggleUndoClose: () -> Unit,
) {
    var showNewTabTypeDialog by remember { mutableStateOf(false) }
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
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.AddCircle,
                    title = stringResource(R.string.workspace_settings_newtab_type_title),
                    subtitle = stringResource(R.string.workspace_settings_newtab_type_desc),
                    value = state.defaultNewTabType.newTabTypeValueLabel(),
                    onClick = { showNewTabTypeDialog = true },
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
                    value = state.layoutModePortrait.surfaceValueLabel(),
                    onClick = { showPortraitDialog = true }
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.StayPrimaryLandscape,
                    title = stringResource(R.string.workspace_settings_layout_mode_landscape_title),
                    subtitle = stringResource(R.string.workspace_settings_layout_mode_landscape_desc),
                    value = state.layoutModeLandscape.surfaceValueLabel(),
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
                SettingsSwitchItem(
                    icon = Icons.TwoTone.SettingsBackupRestore,
                    title = stringResource(R.string.workspace_settings_undoclose_title),
                    subtitle = stringResource(R.string.workspace_settings_undoclose_desc),
                    checked = state.undoCloseEnabled,
                    onCheckedChange = { onToggleUndoClose() },
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

    if (showNewTabTypeDialog) {
        val context = LocalContext.current
        LayoutPickerDialog(
            title = stringResource(R.string.workspace_settings_newtab_type_title),
            options = listOf(
                LayoutPickerOption(
                    icon = Workspace.Type.TEMPLATES.icon,
                    label = stringResource(R.string.workspace_settings_newtab_type_ask),
                    description = stringResource(R.string.workspace_settings_newtab_type_ask_desc),
                    selected = state.defaultNewTabType == Workspace.Type.TEMPLATES,
                    onSelect = {
                        onSetDefaultNewTabType(Workspace.Type.TEMPLATES)
                        showNewTabTypeDialog = false
                    },
                ),
            ) + state.newTabCandidates.map { template ->
                LayoutPickerOption(
                    icon = template.icon,
                    label = template.title.get(context),
                    description = template.subtitle.get(context),
                    selected = state.defaultNewTabType == template.type,
                    onSelect = {
                        onSetDefaultNewTabType(template.type)
                        showNewTabTypeDialog = false
                    },
                )
            },
            onDismiss = { showNewTabTypeDialog = false },
        )
    }

    if (showPortraitDialog) {
        LayoutPickerDialog(
            title = stringResource(R.string.workspace_settings_layout_mode_portrait_title),
            options = WorkspaceLayoutSurface.entries.map { surface ->
                LayoutPickerOption(
                    icon = surface.icon(),
                    label = surface.label(),
                    description = surface.description(),
                    selected = state.layoutModePortrait.surface == surface,
                    onSelect = {
                        onSetLayoutModePortrait(surface.toPanelMode())
                        showPortraitDialog = false
                    },
                )
            },
            onDismiss = { showPortraitDialog = false },
        )
    }

    if (showLandscapeDialog) {
        LayoutPickerDialog(
            title = stringResource(R.string.workspace_settings_layout_mode_landscape_title),
            options = WorkspaceLayoutSurface.entries.map { surface ->
                LayoutPickerOption(
                    icon = surface.icon(),
                    label = surface.label(),
                    description = surface.description(),
                    selected = state.layoutModeLandscape.surface == surface,
                    onSelect = {
                        onSetLayoutModeLandscape(surface.toPanelMode())
                        showLandscapeDialog = false
                    },
                )
            },
            onDismiss = { showLandscapeDialog = false },
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

@Composable
private fun Workspace.Type.newTabTypeValueLabel(): String = when (this) {
    Workspace.Type.TEMPLATES -> stringResource(R.string.workspace_settings_newtab_type_ask)
    else -> label.get(LocalContext.current)
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSettingsScreenPreview() {
    WorkspaceSettingsScreen(
        state = WorkspaceSettingsViewModel.State(
            swipeGesturesEnabled = true,
            onDemandWorkspaceCreation = true,
            defaultNewTabType = Workspace.Type.TEMPLATES,
            livePreview = true,
            layoutModePortrait = WorkspacePanelMode.AUTO,
            layoutModeLandscape = WorkspacePanelMode.AUTO,
            paneClickToFocus = true,
            sessionRestoreEnabled = true,
            undoCloseEnabled = true,
            sessionWorkspaceCount = 3,
            sessionDatabaseSizeBytes = 131072,
        ),
        onNavigateUp = {},
        onToggleSwipeGestures = {},
        onToggleOnDemandWorkspaceCreation = {},
        onSetDefaultNewTabType = {},
        onToggleLivePreview = {},
        onSetLayoutModePortrait = {},
        onSetLayoutModeLandscape = {},
        onTogglePaneClickToFocus = {},
        onToggleSessionRestore = {},
        onToggleAutoPause = {},
        onSetAutoPauseIdleTimeout = {},
        onToggleUndoClose = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSettingsScreenPinnedLayoutPreview() {
    WorkspaceSettingsScreen(
        state = WorkspaceSettingsViewModel.State(
            swipeGesturesEnabled = true,
            onDemandWorkspaceCreation = true,
            defaultNewTabType = Workspace.Type.TEMPLATES,
            livePreview = true,
            layoutModePortrait = WorkspacePanelMode.DUAL_VERTICAL,
            layoutModeLandscape = WorkspacePanelMode.ADAPTIVE,
            paneClickToFocus = true,
            sessionRestoreEnabled = true,
            undoCloseEnabled = true,
            sessionWorkspaceCount = 3,
            sessionDatabaseSizeBytes = 131072,
        ),
        onNavigateUp = {},
        onToggleSwipeGestures = {},
        onToggleOnDemandWorkspaceCreation = {},
        onSetDefaultNewTabType = {},
        onToggleLivePreview = {},
        onSetLayoutModePortrait = {},
        onSetLayoutModeLandscape = {},
        onTogglePaneClickToFocus = {},
        onToggleSessionRestore = {},
        onToggleAutoPause = {},
        onSetAutoPauseIdleTimeout = {},
        onToggleUndoClose = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSettingsScreenNewTabTypePreview() {
    WorkspaceSettingsScreen(
        state = WorkspaceSettingsViewModel.State(
            swipeGesturesEnabled = true,
            onDemandWorkspaceCreation = true,
            defaultNewTabType = Workspace.Type.EXPLORER,
            livePreview = true,
            layoutModePortrait = WorkspacePanelMode.AUTO,
            layoutModeLandscape = WorkspacePanelMode.AUTO,
            paneClickToFocus = true,
            sessionRestoreEnabled = true,
            undoCloseEnabled = true,
            sessionWorkspaceCount = 3,
            sessionDatabaseSizeBytes = 131072,
        ),
        onNavigateUp = {},
        onToggleSwipeGestures = {},
        onToggleOnDemandWorkspaceCreation = {},
        onSetDefaultNewTabType = {},
        onToggleLivePreview = {},
        onSetLayoutModePortrait = {},
        onSetLayoutModeLandscape = {},
        onTogglePaneClickToFocus = {},
        onToggleSessionRestore = {},
        onToggleAutoPause = {},
        onSetAutoPauseIdleTimeout = {},
        onToggleUndoClose = {},
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
            onSetDefaultNewTabType = { type -> vm.setDefaultNewTabType(type) },
            onToggleLivePreview = { vm.toggleLivePreview() },
            onSetLayoutModePortrait = { mode -> vm.setLayoutModePortrait(mode) },
            onSetLayoutModeLandscape = { mode -> vm.setLayoutModeLandscape(mode) },
            onTogglePaneClickToFocus = { vm.togglePaneClickToFocus() },
            onToggleSessionRestore = { vm.toggleSessionRestore() },
            onToggleAutoPause = { vm.toggleAutoPause() },
            onSetAutoPauseIdleTimeout = { timeout -> vm.setAutoPauseIdleTimeout(timeout) },
            onToggleUndoClose = { vm.toggleUndoClose() },
        )
    }
}