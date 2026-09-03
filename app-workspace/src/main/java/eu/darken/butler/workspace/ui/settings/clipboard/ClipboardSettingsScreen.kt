package eu.darken.butler.workspace.ui.settings.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ContentPasteOff
import androidx.compose.material.icons.twotone.Numbers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import androidx.compose.runtime.collectAsState
import eu.darken.butler.workspace.R

@Composable
fun ClipboardSettingsScreen(
    modifier: Modifier = Modifier,
    state: ClipboardSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onToggleRemoveOnPaste: () -> Unit,
    onSetMaxItems: (Int) -> Unit,
    onUpgradeButler: () -> Unit,
) {
    var showMaxItemsDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clipboard_settings_title)) },
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
                    icon = Icons.TwoTone.ContentPasteOff,
                    title = stringResource(R.string.clipboard_settings_remove_on_paste_title),
                    subtitle = stringResource(R.string.clipboard_settings_remove_on_paste_desc),
                    checked = state.removeOnPaste,
                    onCheckedChange = { onToggleRemoveOnPaste() },
                    onUpgrade = if (state.isUpgraded) null else onUpgradeButler,
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Numbers,
                    title = stringResource(R.string.clipboard_settings_max_items_title),
                    subtitle = stringResource(R.string.clipboard_settings_max_items_desc),
                    value = state.maxItems.toString(),
                    onClick = { showMaxItemsDialog = true },
                    onUpgrade = if (state.isUpgraded) null else onUpgradeButler,
                )
            }
        }
    }

    if (showMaxItemsDialog) {
        MaxItemsDialog(
            currentValue = state.maxItems,
            onDismiss = { showMaxItemsDialog = false },
            onConfirm = { value ->
                onSetMaxItems(value)
                showMaxItemsDialog = false
            }
        )
    }
}

@Composable
private fun MaxItemsDialog(
    currentValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val options = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clipboard_settings_max_items_title)) },
        text = {
            Column {
                options.forEach { value ->
                    val isSelected = value == currentValue
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = { onConfirm(value) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onConfirm(value) }
                        )
                        Text(
                            text = value.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardSettingsScreenPreview() {
    ClipboardSettingsScreen(
        state = ClipboardSettingsViewModel.State(
            removeOnPaste = false,
            maxItems = 3,
            isUpgraded = true,
        ),
        onNavigateUp = {},
        onToggleRemoveOnPaste = {},
        onSetMaxItems = {},
        onUpgradeButler = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardSettingsScreenLockedPreview() {
    ClipboardSettingsScreen(
        state = ClipboardSettingsViewModel.State(
            removeOnPaste = false,
            maxItems = 3,
            isUpgraded = false,
        ),
        onNavigateUp = {},
        onToggleRemoveOnPaste = {},
        onSetMaxItems = {},
        onUpgradeButler = {},
    )
}

@Composable
fun ClipboardSettingsScreenHost(vm: ClipboardSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { vmState ->
        ClipboardSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onToggleRemoveOnPaste = { vm.toggleRemoveOnPaste() },
            onSetMaxItems = { vm.setMaxItems(it) },
            onUpgradeButler = { vm.upgradeButler() },
        )
    }
}
