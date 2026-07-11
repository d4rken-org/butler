package eu.darken.butler.main.ui.settings.shortcuts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.DynamicFeed
import androidx.compose.material.icons.twotone.FormatListNumbered
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsBaseItem
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import androidx.compose.runtime.collectAsState

@Composable
fun ShortcutsSettingsScreenHost(vm: ShortcutsSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { state ->
        ShortcutsSettingsScreen(
            state = state,
            onNavigateUp = { vm.navUp() },
            onToggleEnabled = { vm.updateEnabled(it) },
            onToggleAutoRemember = { vm.updateAutoRemember(it) },
            onMaxShortcutsChanged = { vm.updateMaxShortcuts(it) },
            onMinAccessChanged = { vm.updateMinAccessCount(it) },
            onClearHistory = { vm.clearShortcuts() },
            onEvent = { vm.onEvent(it) },
        )
    }
}

@Composable
fun ShortcutsSettingsScreen(
    state: ShortcutsSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoRemember: (Boolean) -> Unit,
    onMaxShortcutsChanged: (Int) -> Unit,
    onMinAccessChanged: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onEvent: (ShortcutsSettingsViewModel.Event) -> Unit,
) {
    val context = LocalContext.current
    var showMaxShortcutsDialog by remember { mutableStateOf(false) }
    var showMinAccessDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.shortcuts_settings_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_navigate_up_action),
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = remember { SnackbarHostState() }.also { snackbarHostState ->
                    LaunchedEffect(state.lastEvent) {
                        when (state.lastEvent) {
                            is ShortcutsSettingsViewModel.Event.ShortcutsCleared -> {
                                snackbarHostState.showSnackbar(
                                    message = state.lastEvent.message.get(context)
                                )
                                onEvent(ShortcutsSettingsViewModel.Event.EventConsumed)
                            }
                            else -> {}
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.shortcuts_settings_enabled_title),
                    subtitle = stringResource(R.string.shortcuts_settings_enabled_subtitle),
                    onClick = { onToggleEnabled(!state.isEnabled) },
                    icon = Icons.TwoTone.DynamicFeed,
                    trailingContent = {
                        Switch(
                            checked = state.isEnabled,
                            onCheckedChange = onToggleEnabled,
                        )
                    },
                )
                SettingsDivider()
            }

            if (state.isEnabled) {

                item { SettingsCategoryHeader(stringResource(R.string.shortcuts_settings_last_accessed_title)) }

                item {
                    SettingsBaseItem(
                        title = stringResource(R.string.shortcuts_settings_auto_remember_title),
                        subtitle = stringResource(R.string.shortcuts_settings_auto_remember_subtitle),
                        onClick = { onToggleAutoRemember(!state.autoRememberEnabled) },
                        icon = Icons.TwoTone.History,
                        trailingContent = {
                            Switch(
                                checked = state.autoRememberEnabled,
                                onCheckedChange = onToggleAutoRemember,
                            )
                        },
                    )
                    SettingsDivider()
                }

                if (state.autoRememberEnabled) {
                    item {
                        SettingsBaseItem(
                            title = stringResource(R.string.shortcuts_settings_max_count_title),
                            subtitle = stringResource(
                                R.string.shortcuts_settings_max_count_subtitle,
                                state.maxShortcuts
                            ),
                            onClick = { showMaxShortcutsDialog = true },
                            icon = Icons.TwoTone.FormatListNumbered,
                            trailingContent = {
                                Text(
                                    text = state.maxShortcuts.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                        SettingsDivider()
                    }

                    item {
                        SettingsBaseItem(
                            title = stringResource(R.string.shortcuts_settings_min_access_title),
                            subtitle = stringResource(
                                R.string.shortcuts_settings_min_access_subtitle,
                                state.minAccessCount
                            ),
                            onClick = { showMinAccessDialog = true },
                            icon = Icons.TwoTone.TouchApp,
                            trailingContent = {
                                Text(
                                    text = state.minAccessCount.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                        SettingsDivider()
                    }

                    item {
                        SettingsBaseItem(
                            title = stringResource(R.string.shortcuts_settings_current_title),
                            subtitle = stringResource(
                                R.string.shortcuts_settings_current_subtitle,
                                state.currentShortcuts
                            ),
                            onClick = {},
                            icon = Icons.TwoTone.Info,
                        )
                        SettingsDivider()
                    }

                    item {
                        SettingsBaseItem(
                            title = stringResource(R.string.shortcuts_settings_clear_title),
                            subtitle = stringResource(R.string.shortcuts_settings_clear_subtitle),
                            onClick = { showClearConfirmDialog = true },
                            icon = Icons.TwoTone.DeleteSweep,
                        )
                    }
                }
            }
        }
    }

    // Max shortcuts dialog
    if (showMaxShortcutsDialog) {
        var sliderValue by remember { mutableFloatStateOf(state.maxShortcuts.toFloat()) }
        AlertDialog(
            onDismissRequest = { showMaxShortcutsDialog = false },
            title = { Text(stringResource(R.string.shortcuts_settings_max_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.shortcuts_settings_max_dialog_message))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 1f..4f,
                        steps = 2,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    Text(
                        text = sliderValue.toInt().toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMaxShortcutsChanged(sliderValue.toInt())
                        showMaxShortcutsDialog = false
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_save_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMaxShortcutsDialog = false }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }

    // Min access count dialog
    if (showMinAccessDialog) {
        var sliderValue by remember { mutableFloatStateOf(state.minAccessCount.toFloat()) }
        AlertDialog(
            onDismissRequest = { showMinAccessDialog = false },
            title = { Text(stringResource(R.string.shortcuts_settings_min_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.shortcuts_settings_min_dialog_message))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    Text(
                        text = sliderValue.toInt().toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMinAccessChanged(sliderValue.toInt())
                        showMinAccessDialog = false
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_save_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMinAccessDialog = false }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }

    // Clear confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(stringResource(R.string.shortcuts_settings_clear_confirm_title)) },
            text = { Text(stringResource(R.string.shortcuts_settings_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(eu.darken.butler.common.R.string.general_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShortcutsSettingsScreenPreview() {
    ShortcutsSettingsScreen(
        state = ShortcutsSettingsViewModel.State(
            isEnabled = true,
            autoRememberEnabled = true,
            maxShortcuts = 3,
            minAccessCount = 3,
        ),
        onNavigateUp = {},
        onToggleEnabled = {},
        onToggleAutoRemember = {},
        onMaxShortcutsChanged = {},
        onMinAccessChanged = {},
        onClearHistory = {},
        onEvent = {},
    )
}