package eu.darken.butler.explorer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.twotone.Storage
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
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.explorer.R

@Composable
fun ExplorerSettingsScreen(
    state: ExplorerSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleRegexPatterns: (Boolean) -> Unit,
    onToggleBackButtonNavigation: (Boolean) -> Unit,
    onToggleRecycleBin: (Boolean) -> Unit,
) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explorer_settings_title)) },
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
                SettingsCategoryHeader(text = stringResource(R.string.explorer_settings_file_display))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.FilterList,
                    title = stringResource(R.string.explorer_settings_filter_regex_title),
                    subtitle = stringResource(R.string.explorer_settings_filter_regex_desc),
                    checked = state.useRegexPatterns,
                    onCheckedChange = onToggleRegexPatterns,
                )
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.explorer_settings_navigation))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.AutoMirrored.TwoTone.ArrowBack,
                    title = stringResource(R.string.explorer_settings_back_button_title),
                    subtitle = stringResource(R.string.explorer_settings_back_button_desc),
                    checked = state.useBackButtonForNavigation,
                    onCheckedChange = onToggleBackButtonNavigation,
                )
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.explorer_settings_recyclebin_category))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Delete,
                    title = stringResource(R.string.explorer_settings_recyclebin_enabled_title),
                    subtitle = stringResource(R.string.explorer_settings_recyclebin_enabled_desc),
                    checked = state.recycleBinEnabled,
                    onCheckedChange = onToggleRecycleBin,
                )
            }

            if (state.recycleBinEnabled) {
                item {
                    SettingsPreferenceItem(
                        icon = Icons.TwoTone.DeleteSweep,
                        title = stringResource(R.string.explorer_settings_recyclebin_auto_delete_title),
                        subtitle = stringResource(
                            R.string.explorer_settings_recyclebin_auto_delete_desc,
                            state.recycleBinAutoDeleteDays
                        ),
                        value = stringResource(
                            R.string.explorer_settings_recyclebin_auto_delete_value,
                            state.recycleBinAutoDeleteDays
                        ),
                        onClick = { /* Could open a dialog to change value */ },
                        enabled = false, // Read-only for now
                    )
                    SettingsDivider()
                }

                item {
                    SettingsPreferenceItem(
                        icon = Icons.TwoTone.Storage,
                        title = stringResource(R.string.explorer_settings_recyclebin_max_size_title),
                        subtitle = stringResource(
                            R.string.explorer_settings_recyclebin_max_size_desc,
                            state.recycleBinMaxSizeMB
                        ),
                        value = stringResource(
                            R.string.explorer_settings_recyclebin_max_size_value,
                            state.recycleBinMaxSizeMB
                        ),
                        onClick = { /* Could open a dialog to change value */ },
                        enabled = false, // Read-only for now
                    )
                }
            }
        }
    }
}


@Composable
fun ExplorerSettingsScreenHost(vm: ExplorerSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { vmState ->
        ExplorerSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onToggleRegexPatterns = { vm.toggleRegexPatterns(it) },
            onToggleBackButtonNavigation = { vm.toggleBackButtonNavigation(it) },
            onToggleRecycleBin = { vm.toggleRecycleBin(it) },
        )
    }
}