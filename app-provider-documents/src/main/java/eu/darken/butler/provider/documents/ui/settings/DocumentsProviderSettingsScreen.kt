package eu.darken.butler.provider.documents.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.FolderOpen
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
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.provider.documents.R

@Composable
fun DocumentsProviderSettingsScreenHost(
    vm: DocumentsProviderSettingsViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm, vm.navController)
    val state by waitForState(vm.state)
    state?.let {
        DocumentsProviderSettingsScreen(
            state = it,
            onNavigateUp = { vm.navUp() },
            onToggleEnabled = { vm.updateEnabled(it) },
        )
    }
}

@Composable
fun DocumentsProviderSettingsScreen(
    state: DocumentsProviderSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.provider_documents_settings_title)) },
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
                SettingsCategoryHeader(
                    text = stringResource(R.string.provider_documents_settings_category_integration_label)
                )
            }
            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.FolderOpen,
                    title = stringResource(R.string.provider_documents_enabled_title),
                    subtitle = stringResource(R.string.provider_documents_enabled_desc),
                    checked = state.isEnabled,
                    onCheckedChange = onToggleEnabled,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun DocumentsProviderSettingsScreenPreview() {
    PreviewWrapper {
        DocumentsProviderSettingsScreen(
            state = DocumentsProviderSettingsViewModel.State(
                isEnabled = true,
            ),
            onNavigateUp = {},
            onToggleEnabled = {},
        )
    }
}
