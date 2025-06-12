package eu.darken.butler.main.ui.settings.general

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Translate
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
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.settings.common.SettingsCategoryHeader
import eu.darken.butler.main.ui.settings.common.SettingsDivider
import eu.darken.butler.main.ui.settings.common.SettingsPreferenceItem
import eu.darken.butler.main.ui.settings.common.SettingsSwitchItem

@Composable
fun GeneralSettingsScreen(
    state: GeneralSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onLanguageSwitcher: (() -> Unit)?,
    onFilePreviewsChange: (Boolean) -> Unit,
) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.general_settings_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(
                                    eu.darken
                                        .butler
                                        .common
                                        .R
                                        .string
                                        .general_back_action
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
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_ui_label))
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.ui_theme_mode_setting_label),
                    subtitle = stringResource(R.string.ui_theme_mode_setting_explanation),
                    value = stringResource(R.string.ui_theme_mode_system_label),
                    onClick = {}
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.ui_theme_style_setting_label),
                    subtitle = stringResource(R.string.ui_theme_style_setting_explanation),
                    value = stringResource(R.string.ui_theme_style_default_label),
                    onClick = {}
                )
                SettingsDivider()
            }

            onLanguageSwitcher?.let { action ->
                item {
                    SettingsPreferenceItem(
                        icon = Icons.Default.Translate,
                        title = stringResource(R.string.ui_language_override_label),
                        subtitle = stringResource(R.string.ui_language_override_desc),
                        onClick = { action.invoke() }
                    )
                    SettingsDivider()
                }
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Preview,
                    title = stringResource(R.string.ui_previews_title),
                    subtitle = stringResource(R.string.ui_previews_summary),
                    checked = state.filePreviews,
                    onCheckedChange = onFilePreviewsChange
                )
            }
        }
    }
}

@Preview2
@Composable
private fun GeneralSettingsScreenPreview() {
    PreviewWrapper {
        GeneralSettingsScreen(
            state = GeneralSettingsViewModel.State(filePreviews = true),
            onNavigateUp = {},
            onLanguageSwitcher = {},
            onFilePreviewsChange = {},
        )
    }
}

@Composable
fun GeneralSettingsScreenHost(vm: GeneralSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        GeneralSettingsScreen(
            state = state,
            onNavigateUp = { vm.goTo(null) },
            onLanguageSwitcher = { vm.showLanguagePicker() },
            onFilePreviewsChange = { vm.updateFilePreviews(it) },
        )
    }
}
