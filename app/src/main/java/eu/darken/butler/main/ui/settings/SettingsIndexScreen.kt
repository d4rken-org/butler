package eu.darken.butler.main.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.main.ui.settings.common.SettingsBaseItem
import eu.darken.butler.main.ui.settings.common.SettingsCategoryHeader
import eu.darken.butler.main.ui.settings.common.SettingsDivider

@Composable
fun SettingsIndexScreenHost(vm: SettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        SettingsIndexScreen(
            state = state,
            onNavigateUp = { vm.goTo(null) },
            onNavigateToGeneral = { vm.goTo(AppNav.Settings.General) },
            onNavigateToSupport = { vm.goTo(AppNav.Settings.Support) },
            onNavigateToAcknowledgements = { vm.goTo(AppNav.Settings.Acknowledgements) },
            onOpenUrl = { vm.openUrl(it) },
        )
    }
}

@Composable
fun SettingsIndexScreen(
    state: SettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onNavigateToGeneral: () -> Unit,
    onNavigateToSupport: () -> Unit,
    onNavigateToAcknowledgements: () -> Unit,
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
                    onClick = onNavigateToGeneral,
                )
                SettingsDivider()
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_category_other_label)) }

            item {
                SettingsBaseItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_support_label),
                    subtitle = stringResource(R.string.settings_support_description),
                    onClick = onNavigateToSupport,
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
                    onClick = onNavigateToAcknowledgements,
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
            onNavigateToGeneral = {},
            onNavigateToSupport = {},
            onNavigateToAcknowledgements = {},
            onOpenUrl = {},
        )
    }
}