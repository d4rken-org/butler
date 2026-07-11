package eu.darken.butler.history.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.QueryStats
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.history.R
import kotlinx.coroutines.launch

@Composable
fun HistorySettingsScreen(
    state: HistorySettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onSaveHistoryChange: (Boolean) -> Unit,
    onMaxHistoryItemsChange: (Int) -> Unit,
    onClearHistory: () -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showMaxDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action),
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
            verticalArrangement = Arrangement.Top,
        ) {
            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.History,
                    title = stringResource(R.string.history_settings_save_history_title),
                    subtitle = stringResource(R.string.history_settings_save_history_subtitle),
                    checked = state.saveHistory,
                    onCheckedChange = onSaveHistoryChange,
                )
                SettingsDivider()
            }
            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Storage,
                    title = stringResource(R.string.history_settings_max_history_label),
                    subtitle = stringResource(R.string.history_settings_max_history_subtitle),
                    value = state.maxHistoryItems.toString(),
                    onClick = { showMaxDialog = true },
                )
                SettingsDivider()
            }
            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.QueryStats,
                    title = stringResource(R.string.history_settings_current_history_title),
                    subtitle = stringResource(R.string.history_settings_current_history_subtitle),
                    value = state.currentHistoryCount.toString(),
                    onClick = { /* read-only */ },
                )
                SettingsDivider()
            }
            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.ClearAll,
                    title = stringResource(R.string.history_settings_clear_history_title),
                    subtitle = stringResource(R.string.history_settings_clear_history_subtitle),
                    onClick = { showClearDialog = true },
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.history_clear_dialog_title)) },
            text = { Text(stringResource(R.string.history_clear_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    showClearDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.history_cleared_success))
                    }
                }) {
                    Text(
                        stringResource(R.string.history_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            },
        )
    }

    if (showMaxDialog) {
        MaxHistoryItemsDialog(
            currentValue = state.maxHistoryItems,
            onDismiss = { showMaxDialog = false },
            onConfirm = {
                onMaxHistoryItemsChange(it)
                showMaxDialog = false
            },
        )
    }
}

@Composable
fun HistorySettingsScreenHost(vm: HistorySettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)
    val state by vm.state.collectAsState(initial = null)
    state?.let { s ->
        HistorySettingsScreen(
            state = s,
            onNavigateUp = { vm.navUp() },
            onSaveHistoryChange = { vm.updateSaveHistory(it) },
            onMaxHistoryItemsChange = { vm.updateMaxHistoryItems(it) },
            onClearHistory = { vm.clearHistory() },
        )
    }
}
