package eu.darken.butler.explorer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.FilterList
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
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.waitForState

@Composable
fun ExplorerSettingsScreen(
    state: ExplorerSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleRegexPatterns: (Boolean) -> Unit,
    onToggleBackButtonNavigation: (Boolean) -> Unit,
) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(eu.darken.butler.explorer.R.string.explorer_settings_title)) },
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
                SettingsCategoryHeader(text = stringResource(eu.darken.butler.explorer.R.string.explorer_settings_file_display))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.FilterList,
                    title = stringResource(eu.darken.butler.explorer.R.string.explorer_settings_filter_regex_title),
                    subtitle = stringResource(eu.darken.butler.explorer.R.string.explorer_settings_filter_regex_desc),
                    checked = state.useRegexPatterns,
                    onCheckedChange = onToggleRegexPatterns,
                )
            }

            item {
                SettingsCategoryHeader(text = stringResource(eu.darken.butler.explorer.R.string.explorer_settings_navigation))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.AutoMirrored.TwoTone.ArrowBack,
                    title = stringResource(eu.darken.butler.explorer.R.string.explorer_settings_back_button_title),
                    subtitle = stringResource(eu.darken.butler.explorer.R.string.explorer_settings_back_button_desc),
                    checked = state.useBackButtonForNavigation,
                    onCheckedChange = onToggleBackButtonNavigation,
                )
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
        )
    }
}