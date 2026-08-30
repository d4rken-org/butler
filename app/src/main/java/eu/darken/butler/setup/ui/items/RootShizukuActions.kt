package eu.darken.butler.setup.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.root.RootServiceState
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule

@Composable
fun RootShizukuActions(
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
                when (rootState.toCardStatus()) {
                    RootCardStatus.DISABLED -> null
                    RootCardStatus.CONNECTED -> stringResource(R.string.setup_status_connected)
                    RootCardStatus.CONNECTING -> stringResource(R.string.setup_status_connecting)
                    RootCardStatus.NOT_INSTALLED -> stringResource(R.string.setup_status_not_installed)
                    RootCardStatus.NOT_CONNECTED -> stringResource(R.string.setup_status_not_connected)
                }
            }
            SetupModule.Type.SHIZUKU -> {
                val shizukuState = state as? ShizukuSetupModule.Result
                when {
                    shizukuState?.useShizuku != true -> null
                    !shizukuState.isInstalled -> stringResource(R.string.setup_status_not_installed)
                    !shizukuState.isCompatible -> stringResource(R.string.setup_status_unavailable)
                    shizukuState.ourService -> stringResource(R.string.setup_status_connected)
                    // Ahead of basicService: Shizuku itself answering says nothing about our service,
                    // and reporting "Connecting…" for a probe that already gave up is what left this
                    // card spinning forever.
                    shizukuState.serviceState.isTerminalFailure ->
                        stringResource(R.string.setup_status_connection_failed)

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
                            onExecuteAction(SetupAction.ToggleRoot(if (enabled) true else null))
                        }
                        SetupModule.Type.SHIZUKU -> {
                            onExecuteAction(SetupAction.ToggleShizuku(if (enabled) true else null))
                        }
                        else -> {}
                    }
                }
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RootActionsEnabledPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.ROOT,
            state = RootSetupModule.Result(
                useRoot = true,
                isInstalled = true,
                serviceState = RootServiceState.Available,
            ),
            isRequired = true,
            priority = 5,
        ),
        onExecuteAction = {},
        switchLabel = "Use Root"
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RootActionsConnectedWithoutManagerPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.ROOT,
            state = RootSetupModule.Result(
                useRoot = true,
                // A rooted device whose root manager is none of the ones we can look up.
                isInstalled = false,
                serviceState = RootServiceState.Available,
            ),
            isRequired = true,
            priority = 5,
        ),
        onExecuteAction = {},
        switchLabel = "Use Root"
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun RootActionsConnectingPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.ROOT,
            state = RootSetupModule.Result(
                useRoot = true,
                isInstalled = true,
                serviceState = RootServiceState.Connecting,
            ),
            isRequired = true,
            priority = 5,
        ),
        onExecuteAction = {},
        switchLabel = "Use Root"
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsNotConnectedPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.SHIZUKU,
            state = ShizukuSetupModule.Result(
                pkg = "moe.shizuku.privileged.api".toPkgId(),
                useShizuku = true,
                isCompatible = true,
                isInstalled = true,
                basicService = false,
                serviceState = ShizukuServiceState.NotChecked,
                alsoHasRoot = false,
            ),
            isRequired = false,
            priority = 6,
        ),
        onExecuteAction = {},
        switchLabel = "Use Shizuku"
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsConnectionFailedPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.SHIZUKU,
            state = ShizukuSetupModule.Result(
                pkg = "moe.shizuku.privileged.api".toPkgId(),
                useShizuku = true,
                isCompatible = true,
                isInstalled = true,
                // Shizuku answers, but our user service never came up.
                basicService = true,
                serviceState = ShizukuServiceState.TimedOut,
                alsoHasRoot = false,
            ),
            isRequired = false,
            priority = 6,
        ),
        onExecuteAction = {},
        switchLabel = "Use Shizuku"
    )
}