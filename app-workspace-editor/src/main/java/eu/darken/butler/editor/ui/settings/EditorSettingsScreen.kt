package eu.darken.butler.editor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.twotone.FormatListNumbered
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
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.R

@Composable
fun EditorSettingsScreen(
    state: EditorSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onShowLineNumbersChange: (Boolean) -> Unit,
    onWordWrapChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_settings_title)) },
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
                SettingsSwitchItem(
                    icon = Icons.TwoTone.FormatListNumbered,
                    title = stringResource(R.string.editor_settings_show_line_numbers_title),
                    subtitle = stringResource(R.string.editor_settings_show_line_numbers_subtitle),
                    checked = state.showLineNumbers,
                    onCheckedChange = onShowLineNumbersChange
                )
                SettingsDivider()
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.AutoMirrored.Filled.WrapText,
                    title = stringResource(R.string.editor_settings_word_wrap_title),
                    subtitle = stringResource(R.string.editor_settings_word_wrap_subtitle),
                    checked = state.wordWrap,
                    onCheckedChange = onWordWrapChange
                )
            }
        }
    }
}

@Preview2
@Composable
private fun EditorSettingsScreenPreview() {
    PreviewWrapper {
        EditorSettingsScreen(
            state = EditorSettingsViewModel.State(
                showLineNumbers = true,
                wordWrap = false,
            ),
            onNavigateUp = {},
            onShowLineNumbersChange = {},
            onWordWrapChange = {},
        )
    }
}

@Composable
fun EditorSettingsScreenHost(vm: EditorSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { vmState ->
        EditorSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onShowLineNumbersChange = { vm.updateShowLineNumbers(it) },
            onWordWrapChange = { vm.updateWordWrap(it) },
        )
    }
}
