package eu.darken.butler.viewer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.viewer.R

@Composable
fun ViewerSettingsScreen(
    state: ViewerSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onShowNextAfterDeleteChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.viewer_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top,
        ) {
            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.SkipNext,
                    title = stringResource(R.string.viewer_settings_show_next_after_delete_title),
                    subtitle = stringResource(R.string.viewer_settings_show_next_after_delete_subtitle),
                    checked = state.showNextAfterDelete,
                    onCheckedChange = onShowNextAfterDeleteChange,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ViewerSettingsScreenPreview() {
    ViewerSettingsScreen(
        state = ViewerSettingsViewModel.State(showNextAfterDelete = true),
        onNavigateUp = {},
        onShowNextAfterDeleteChange = {},
    )
}

@Composable
fun ViewerSettingsScreenHost(vm: ViewerSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { vmState ->
        ViewerSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onShowNextAfterDeleteChange = { vm.updateShowNextAfterDelete(it) },
        )
    }
}
