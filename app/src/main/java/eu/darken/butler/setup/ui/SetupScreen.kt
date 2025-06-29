package eu.darken.butler.setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: SetupViewModel.State,
    onNavigateUp: () -> Unit,
    onExecuteAction: (SetupModule.Type, SetupAction) -> Unit,
    onOpenHelp: (SetupModule.Type) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
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
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(state.items) { item ->
                SetupCard(
                    item = item,
                    onExecuteAction = { action -> onExecuteAction(item.type, action) },
                    onOpenHelp = { onOpenHelp(item.type) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SetupCard(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit,
    onOpenHelp: () -> Unit,
) {
    val cardColor = when (val state = item.state) {
        is SetupModule.State.Current -> {
            when {
                state.isComplete -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surface
            }
        }
        else -> MaterialTheme.colorScheme.surface
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top row: Icon + Title on left, Help button on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = getSetupIcon(item.type),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = stringResource(item.type.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = onOpenHelp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Help,
                        contentDescription = stringResource(R.string.setup_help_description),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Description text taking full width
            Text(
                text = getSetupDescription(item.type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Actions (status + button/switch)
            SetupActions(
                item = item,
                onExecuteAction = onExecuteAction
            )
        }
    }
}

@Composable
private fun SetupStateIndicator(
    state: SetupModule.State,
    isRequired: Boolean
) {
    when (state) {
        is SetupModule.State.Loading -> {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
        is SetupModule.State.Current -> {
            // Special handling for Root/Shizuku connection status
            val (icon, tint) = when (state.type) {
                SetupModule.Type.ROOT -> {
                    val rootState = state as? RootSetupModule.Result
                    when {
                        rootState?.useRoot != true -> {
                            Icons.Default.PauseCircle to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                        !rootState.isInstalled -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.error
                        }
                        rootState.ourService -> {
                            Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        }
                        else -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.tertiary
                        }
                    }
                }
                SetupModule.Type.SHIZUKU -> {
                    val shizukuState = state as? ShizukuSetupModule.Result
                    when {
                        shizukuState?.useShizuku != true -> {
                            Icons.Default.PauseCircle to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                        !shizukuState.isInstalled || !shizukuState.isCompatible -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.error
                        }
                        shizukuState.ourService -> {
                            Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        }
                        shizukuState.basicService -> {
                            Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                        else -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.tertiary
                        }
                    }
                }
                else -> {
                    // Default status handling for other permissions
                    when {
                        state.isComplete -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        isRequired -> Icons.Default.Error to MaterialTheme.colorScheme.error
                        else -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    }
                }
            }
            
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = tint
            )
        }
    }
}

@Composable
private fun SetupActions(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit
) {
    when (item.type) {
        SetupModule.Type.ROOT -> {
            RootShizukuActions(
                item = item,
                onExecuteAction = onExecuteAction,
                switchLabel = stringResource(R.string.setup_use_root_label)
            )
        }
        SetupModule.Type.SHIZUKU -> {
            RootShizukuActions(
                item = item,
                onExecuteAction = onExecuteAction,
                switchLabel = stringResource(R.string.setup_use_shizuku_label)
            )
        }
        else -> {
            DefaultActions(item = item, onExecuteAction = onExecuteAction)
        }
    }
}

@Composable
private fun RootShizukuActions(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit,
    switchLabel: String
) {
    val state = item.state as? SetupModule.State.Current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection status for Root/Shizuku
        val connectionStatus = when (item.type) {
            SetupModule.Type.ROOT -> {
                val rootState = state as? RootSetupModule.Result
                when {
                    rootState?.useRoot != true -> null
                    !rootState.isInstalled -> stringResource(R.string.setup_status_not_installed)
                    rootState.ourService -> stringResource(R.string.setup_status_connected)
                    else -> stringResource(R.string.setup_status_not_connected)
                }
            }
            SetupModule.Type.SHIZUKU -> {
                val shizukuState = state as? ShizukuSetupModule.Result
                when {
                    shizukuState?.useShizuku != true -> null
                    !shizukuState.isInstalled -> stringResource(R.string.setup_status_not_installed)
                    !shizukuState.isCompatible -> stringResource(R.string.setup_status_unavailable)
                    shizukuState.ourService -> stringResource(R.string.setup_status_connected)
                    shizukuState.basicService -> stringResource(R.string.setup_status_connecting)
                    else -> stringResource(R.string.setup_status_not_connected)
                }
            }
            else -> null
        }

        // Status indicator and message
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SetupStateIndicator(
                state = item.state,
                isRequired = item.isRequired
            )

            Column {
                Text(
                    text = getStatusMessage(item.state, item.isRequired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = getStatusColor(item.state, item.isRequired)
                )

                connectionStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = switchLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )

            Switch(
                checked = when (item.type) {
                    SetupModule.Type.ROOT -> {
                        (state as? RootSetupModule.Result)?.useRoot == true
                    }
                    SetupModule.Type.SHIZUKU -> {
                        (state as? ShizukuSetupModule.Result)?.useShizuku == true
                    }
                    else -> false
                },
                onCheckedChange = { enabled ->
                    when (item.type) {
                        SetupModule.Type.ROOT -> {
                            onExecuteAction(SetupAction.TOGGLE_ROOT(if (enabled) true else null))
                        }
                        SetupModule.Type.SHIZUKU -> {
                            onExecuteAction(SetupAction.TOGGLE_SHIZUKU(if (enabled) true else null))
                        }
                        else -> {}
                    }
                }
            )
        }
    }
}

@Composable
private fun DefaultActions(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit
) {
    val state = item.state as? SetupModule.State.Current
    val isCompleted = state?.isComplete == true

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status indicator and message
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SetupStateIndicator(
                state = item.state,
                isRequired = item.isRequired
            )

            Text(
                text = getStatusMessage(item.state, item.isRequired),
                style = MaterialTheme.typography.bodyMedium,
                color = getStatusColor(item.state, item.isRequired)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Grant access button
        Button(
            onClick = { onExecuteAction(SetupAction.REFRESH) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isCompleted
        ) {
            Text(
                text = if (isCompleted) {
                    stringResource(R.string.setup_access_granted_label)
                } else {
                    stringResource(R.string.setup_grant_access_label)
                }
            )
        }
    }
}

private fun getSetupIcon(type: SetupModule.Type): ImageVector = when (type) {
    SetupModule.Type.ROOT -> Icons.Default.Security
    SetupModule.Type.SHIZUKU -> Icons.Default.Security
    SetupModule.Type.NOTIFICATION -> Icons.Default.Notifications
    SetupModule.Type.USAGE_STATS -> Icons.Default.Settings
    SetupModule.Type.SAF -> Icons.Default.Storage
    SetupModule.Type.STORAGE -> Icons.Default.Storage
    SetupModule.Type.INVENTORY -> Icons.Default.Inventory
}

@Composable
private fun getSetupDescription(type: SetupModule.Type): String {
    return when (type) {
        SetupModule.Type.ROOT -> stringResource(R.string.setup_root_description)
        SetupModule.Type.SHIZUKU -> stringResource(R.string.setup_shizuku_description)
        SetupModule.Type.NOTIFICATION -> stringResource(R.string.setup_notification_description)
        SetupModule.Type.USAGE_STATS -> stringResource(R.string.setup_usagestats_description)
        SetupModule.Type.SAF -> stringResource(R.string.setup_saf_description)
        SetupModule.Type.STORAGE -> stringResource(R.string.setup_storage_description)
        SetupModule.Type.INVENTORY -> stringResource(R.string.setup_inventory_description)
    }
}

@Composable
private fun getStatusMessage(state: SetupModule.State, isRequired: Boolean): String {
    return when (state) {
        is SetupModule.State.Loading -> stringResource(R.string.setup_status_checking)
        is SetupModule.State.Current -> {
            when {
                state.isComplete -> stringResource(R.string.setup_status_completed)
                isRequired -> stringResource(R.string.setup_status_required)
                else -> stringResource(R.string.setup_status_optional)
            }
        }
    }
}

@Composable
private fun getStatusColor(state: SetupModule.State, isRequired: Boolean): androidx.compose.ui.graphics.Color {
    return when (state) {
        is SetupModule.State.Loading -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        is SetupModule.State.Current -> {
            when {
                state.isComplete -> MaterialTheme.colorScheme.primary
                isRequired -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
        }
    }
}


@Preview2
@Composable
private fun SetupScreenPreview() {
    PreviewWrapper {
        SetupScreen(
            state = SetupViewModel.State(
                items = listOf(
                    SetupItem(
                        type = SetupModule.Type.STORAGE,
                        state = object : SetupModule.State.Current {
                            override val type = SetupModule.Type.STORAGE
                            override val isComplete = true
                        },
                        isRequired = true,
                        priority = 1
                    ),
                    SetupItem(
                        type = SetupModule.Type.ROOT,
                        state = object : SetupModule.State.Current {
                            override val type = SetupModule.Type.ROOT
                            override val isComplete = false
                        },
                        isRequired = false,
                        priority = 5
                    )
                )
            ),
            onNavigateUp = {},
            onExecuteAction = { _, _ -> },
            onOpenHelp = {}
        )
    }
}

@Composable
fun SetupScreenHost(vm: SetupViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { vmState ->
        SetupScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onExecuteAction = { type, action -> vm.executeAction(type, action) },
            onOpenHelp = { type -> vm.openHelp(type) }
        )
    }
}