package eu.darken.butler.searcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.Numbers
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import androidx.compose.runtime.collectAsState
import eu.darken.butler.searcher.R
import kotlinx.coroutines.launch

@Composable
fun SearcherSettingsScreen(
    state: SearcherSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onMaxSearchResultsChange: (Int) -> Unit,
    onMaxHistoryItemsChange: (Int) -> Unit,
    onSaveHistoryChange: (Boolean) -> Unit,
    onContentSearchBinariesChange: (Boolean) -> Unit,
    onClearSearchHistory: () -> Unit,
) {
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showMaxHistoryDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.searcher_settings_title)) },
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
                SettingsCategoryHeader(text = stringResource(R.string.searcher_settings_search_performance))
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Numbers,
                    title = stringResource(R.string.searcher_settings_max_results_title),
                    subtitle = stringResource(R.string.searcher_settings_max_results_subtitle),
                    value = state.maxSearchResults.toString(),
                    onClick = {
                        // Cycle through common preset values
                        val newValue = when (state.maxSearchResults) {
                            100 -> 250
                            250 -> 500
                            500 -> 1000
                            1000 -> 2500
                            2500 -> 5000
                            5000 -> 10000
                            else -> 100  // Reset to default for any other value
                        }
                        onMaxSearchResultsChange(newValue)
                    }
                )
                SettingsDivider()
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.searcher_settings_content_search_category))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Code,
                    title = stringResource(R.string.searcher_settings_include_binaries_title),
                    subtitle = stringResource(R.string.searcher_settings_include_binaries_subtitle),
                    checked = state.contentSearchBinaries,
                    onCheckedChange = onContentSearchBinariesChange
                )
                SettingsDivider()
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.searcher_settings_history))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.History,
                    title = stringResource(R.string.searcher_settings_save_history_title),
                    subtitle = stringResource(R.string.searcher_settings_save_history_subtitle),
                    checked = state.saveHistory,
                    onCheckedChange = onSaveHistoryChange
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Storage,
                    title = stringResource(R.string.searcher_settings_max_history_label),
                    subtitle = stringResource(R.string.searcher_settings_max_history_subtitle),
                    value = state.maxHistoryItems.toString(),
                    onClick = { showMaxHistoryDialog = true },
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.QueryStats,
                    title = stringResource(R.string.searcher_settings_current_history_title),
                    subtitle = stringResource(R.string.searcher_settings_current_history_subtitle),
                    value = state.currentHistoryCount.toString(),
                    onClick = { /* Read-only, no action */ }
                )
                SettingsDivider()
            }
            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.ClearAll,
                    title = stringResource(R.string.searcher_settings_clear_history_title),
                    subtitle = stringResource(R.string.searcher_settings_clear_history_subtitle),
                    onClick = { showClearHistoryDialog = true }
                )
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = {
                Text(text = stringResource(R.string.searcher_history_clear_dialog_title))
            },
            text = {
                Text(text = stringResource(R.string.searcher_history_clear_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearSearchHistory()
                        showClearHistoryDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.searcher_history_cleared_success)
                            )
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.searcher_history_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearHistoryDialog = false }
                ) {
                    Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }

    if (showMaxHistoryDialog) {
        MaxHistoryItemsDialog(
            currentValue = state.maxHistoryItems,
            onDismiss = { showMaxHistoryDialog = false },
            onConfirm = { newValue ->
                onMaxHistoryItemsChange(newValue)
                showMaxHistoryDialog = false
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherSettingsScreenPreview() {
    SearcherSettingsScreen(
        state = SearcherSettingsViewModel.State(
            maxSearchResults = 1000,
            maxHistoryItems = 15,
            saveHistory = true,
            contentSearchBinaries = false,
            currentHistoryCount = 7
        ),
        onNavigateUp = {},
        onMaxSearchResultsChange = {},
        onMaxHistoryItemsChange = {},
        onSaveHistoryChange = {},
        onContentSearchBinariesChange = {},
        onClearSearchHistory = {},
    )
}

@Composable
fun SearcherSettingsScreenHost(vm: SearcherSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { vmState ->
        SearcherSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onMaxSearchResultsChange = { vm.updateMaxSearchResults(it) },
            onMaxHistoryItemsChange = { vm.updateMaxHistoryItems(it) },
            onSaveHistoryChange = { vm.updateSaveHistory(it) },
            onContentSearchBinariesChange = { vm.updateContentSearchBinaries(it) },
            onClearSearchHistory = { vm.clearSearchHistory() },
        )
    }
}
