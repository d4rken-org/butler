package eu.darken.butler.editor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.settings.common.SettingsDivider
import eu.darken.butler.main.ui.settings.common.SettingsSwitchItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSettingsScreen(
    state: EditorSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onShowLineNumbersChange: (Boolean) -> Unit,
) {
    LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor Settings") },
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
                    icon = Icons.Default.TextFormat,
                    title = "Show Line Numbers",
                    subtitle = "Display line numbers in the editor",
                    checked = state.showLineNumbers,
                    onCheckedChange = onShowLineNumbersChange
                )
                SettingsDivider()
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
                showLineNumbers = true
            ),
            onNavigateUp = {},
            onShowLineNumbersChange = {},
        )
    }
}

@Composable
fun EditorSettingsScreenHost(vm: EditorSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { vmState ->
        EditorSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.goTo(null) },
            onShowLineNumbersChange = { vm.updateShowLineNumbers(it) },
        )
    }
}
