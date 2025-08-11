package eu.darken.butler.searcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
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
import eu.darken.butler.searcher.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem

@Composable
fun SearcherSettingsScreen(
    state: SearcherSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onWholeWordChange: (Boolean) -> Unit,
    onUseRegexChange: (Boolean) -> Unit,
    onMaxHistoryItemsChange: (Int) -> Unit,
    onSaveHistoryChange: (Boolean) -> Unit,
    onClearSearchHistory: () -> Unit,
) {
    Scaffold(
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
                SettingsCategoryHeader(text = stringResource(R.string.searcher_settings_search_options))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.searcher_settings_case_sensitive_title),
                    subtitle = stringResource(R.string.searcher_settings_case_sensitive_subtitle),
                    checked = state.caseSensitive,
                    onCheckedChange = onCaseSensitiveChange
                )
                SettingsDivider()
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.searcher_settings_whole_word_title),
                    subtitle = stringResource(R.string.searcher_settings_whole_word_subtitle),
                    checked = state.wholeWord,
                    onCheckedChange = onWholeWordChange
                )
                SettingsDivider()
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.searcher_settings_regex_title),
                    subtitle = stringResource(R.string.searcher_settings_regex_subtitle),
                    checked = state.useRegex,
                    onCheckedChange = onUseRegexChange
                )
                SettingsDivider()
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.searcher_settings_history))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.searcher_settings_save_history_title),
                    subtitle = stringResource(R.string.searcher_settings_save_history_subtitle),
                    checked = state.saveHistory,
                    onCheckedChange = onSaveHistoryChange
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.searcher_settings_max_history_title),
                    subtitle = stringResource(R.string.searcher_settings_max_history_subtitle),
                    value = state.maxHistoryItems.toString(),
                    onClick = {
                        // In a real implementation, this would show a dialog to select the number
                        // For simplicity, we'll just increment the value
                        val newValue = if (state.maxHistoryItems >= 20) 5 else state.maxHistoryItems + 5
                        onMaxHistoryItemsChange(newValue)
                    }
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Default.ClearAll,
                    title = stringResource(R.string.searcher_settings_clear_history_title),
                    subtitle = stringResource(R.string.searcher_settings_clear_history_subtitle),
                    onClick = onClearSearchHistory
                )
            }
        }
    }
}

@Preview2
@Composable
private fun SearcherSettingsScreenPreview() {
    PreviewWrapper {
        SearcherSettingsScreen(
            state = SearcherSettingsViewModel.State(
                caseSensitive = true,
                wholeWord = false,
                useRegex = true,
                maxHistoryItems = 15,
                saveHistory = true
            ),
            onNavigateUp = {},
            onCaseSensitiveChange = {},
            onWholeWordChange = {},
            onUseRegexChange = {},
            onMaxHistoryItemsChange = {},
            onSaveHistoryChange = {},
            onClearSearchHistory = {},
        )
    }
}

@Composable
fun SearcherSettingsScreenHost(vm: SearcherSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { vmState ->
        SearcherSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onCaseSensitiveChange = { vm.updateCaseSensitive(it) },
            onWholeWordChange = { vm.updateWholeWord(it) },
            onUseRegexChange = { vm.updateUseRegex(it) },
            onMaxHistoryItemsChange = { vm.updateMaxHistoryItems(it) },
            onSaveHistoryChange = { vm.updateSaveHistory(it) },
            onClearSearchHistory = { vm.clearSearchHistory() },
        )
    }
}
