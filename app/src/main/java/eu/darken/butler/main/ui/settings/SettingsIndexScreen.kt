package eu.darken.butler.main.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Workspaces
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
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.settings.SettingsBaseItem
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.ui.editor
import eu.darken.butler.explorer.ui.explorer
import eu.darken.butler.searcher.ui.searcher

@Composable
fun SettingsIndexScreenHost(vm: SettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        SettingsIndexScreen(
            state = state,
            onNavigateUp = { vm.navUp() },
            onNavigateTo = { vm.navTo(it) },
            onNavigateToSetup = { vm.navTo(Nav.Main.destSetup()) },
            onOpenUrl = { vm.openUrl(it) },
        )
    }
}

@Composable
fun SettingsIndexScreen(
    state: SettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onNavigateTo: (NavigationDestination) -> Unit,
    onNavigateToSetup: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action)
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
                SettingsBaseItem(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.general_settings_label),
                    subtitle = stringResource(R.string.general_settings_desc),
                    onClick = { onNavigateTo(Nav.Settings.general()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Workspaces,
                    title = stringResource(R.string.workspace_settings_title),
                    subtitle = "Configure workspace behavior",
                    onClick = { onNavigateTo(Nav.Settings.workspaces()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.setup_title),
                    subtitle = stringResource(R.string.setup_settings_description),
                    onClick = { onNavigateToSetup() },
                )
                SettingsDivider()
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_category_tools_label)) }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Folder,
                    title = "Explorer Settings",
                    subtitle = "Configure file explorer behavior",
                    onClick = { onNavigateTo(Nav.Settings.explorer()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Search,
                    title = "Searcher Settings",
                    subtitle = "Configure search behavior",
                    onClick = { onNavigateTo(Nav.Settings.searcher()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Edit,
                    title = "Editor Settings",
                    subtitle = "Configure text editor behavior",
                    onClick = { onNavigateTo(Nav.Settings.editor()) },
                )
                SettingsDivider()
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_category_other_label)) }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_support_label),
                    subtitle = stringResource(R.string.settings_support_description),
                    onClick = { onNavigateTo(Nav.Settings.support()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.AutoMirrored.Filled.ListAlt,
                    title = stringResource(R.string.changelog_label),
                    subtitle = state.versionText,
                    onClick = { onOpenUrl(ButlerLinks.CHANGELOG) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Favorite,
                    title = stringResource(R.string.settings_acknowledgements_label),
                    subtitle = stringResource(R.string.settings_acknowledgements_description),
                    onClick = { onNavigateTo(Nav.Settings.acks()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.PrivacyTip,
                    title = stringResource(R.string.settings_privacy_policy_label),
                    subtitle = stringResource(R.string.settings_privacy_policy_desc),
                    onClick = { onOpenUrl(ButlerLinks.PRIVACY_POLICY) },
                )
            }
        }
    }
}

@Preview2
@Composable
private fun SettingsScreenPreview() {
    PreviewWrapper {
        SettingsIndexScreen(
            state = SettingsViewModel.State(),
            onNavigateUp = {},
            onNavigateTo = {},
            onNavigateToSetup = {},
            onOpenUrl = {},
        )
    }
}
