package eu.darken.butler.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.SwipeLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.waitForState

@Composable
fun WorkspaceSettingsScreen(
    state: WorkspaceSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleSwipeGestures: () -> Unit,
    onToggleOnDemandWorkspaceCreation: () -> Unit,
) {
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
        }
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
            ),
            onNavigateUp = {},
            onToggleSwipeGestures = {},
            onToggleOnDemandWorkspaceCreation = {},
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
        )
    }
}