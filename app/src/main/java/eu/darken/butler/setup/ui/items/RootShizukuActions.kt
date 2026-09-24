package eu.darken.butler.setup.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.AdbPermissionState
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.pkgs.container.toStub
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.root.RootServiceState
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.shizuku.AdbManagerInstallGuide
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule

/** The flavor's install guide; without one the card offers no install action. */
val LocalAdbManagerInstallGuide = staticCompositionLocalOf<AdbManagerInstallGuide?> { null }

@Composable
fun RootShizukuActions(
    modifier: Modifier = Modifier,
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit,
    switchLabel: String,
) {
    val state = item.state as? SetupModule.State.Current
    val adbState = state as? ShizukuSetupModule.Result
    val adbStatus = adbState.toCardStatus()

    Column(
        modifier = modifier.fillMaxWidth(),
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
                    RootCardStatus.NOT_CONNECTED -> stringResource(R.string.setup_status_not_connected)
                    RootCardStatus.CONNECTION_FAILED -> stringResource(R.string.setup_status_connection_failed)
                }
            }
            SetupModule.Type.SHIZUKU -> when (adbStatus) {
                AdbCardStatus.DISABLED -> null
                AdbCardStatus.NOT_INSTALLED -> stringResource(R.string.setup_status_not_installed)
                AdbCardStatus.BUTLER_UPDATE_REQUIRED -> stringResource(R.string.setup_adb_status_butler_update_required)
                AdbCardStatus.MANAGER_UPDATE_REQUIRED -> stringResource(R.string.setup_adb_status_manager_update_required)
                AdbCardStatus.CONNECTED -> adbState?.managerName
                    ?.let { stringResource(R.string.setup_adb_status_connected_via, it) }
                    ?: stringResource(R.string.setup_status_connected)
                AdbCardStatus.PERMISSION_DENIED -> stringResource(R.string.setup_status_permission_denied)
                AdbCardStatus.CONNECTION_FAILED -> stringResource(R.string.setup_status_connection_failed)
                AdbCardStatus.CONNECTING -> stringResource(R.string.setup_status_connecting)
                AdbCardStatus.NOT_CONNECTED -> stringResource(R.string.setup_status_not_connected)
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

        if (adbState != null) {
            AdbManagerAction(
                state = adbState,
                status = adbStatus,
                onExecuteAction = onExecuteAction,
            )
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

/** The module resolves the label; when it could not, the product name stands in, never the package id. */
private val ShizukuSetupModule.Result.managerName: String?
    get() = managerLabel ?: backend?.label

@Composable
private fun AdbManagerAction(
    modifier: Modifier = Modifier,
    state: ShizukuSetupModule.Result,
    status: AdbCardStatus,
    onExecuteAction: (SetupAction) -> Unit,
) {
    val installGuide = LocalAdbManagerInstallGuide.current
    val openTarget = state.pkg
    val managerName = state.managerName

    when {
        status == AdbCardStatus.DISABLED || status == AdbCardStatus.BUTLER_UPDATE_REQUIRED -> {}

        status == AdbCardStatus.NOT_INSTALLED -> if (installGuide != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                modifier = modifier,
                onClick = { onExecuteAction(SetupAction.InstallAdbManager) },
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Download,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.setup_adb_install_manager_action, installGuide.backend.label))
            }
        }

        openTarget != null && managerName != null -> {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                modifier = modifier,
                onClick = { onExecuteAction(SetupAction.OpenAdbManager(openTarget)) },
            ) {
                AsyncImage(
                    // Through Coil rather than a PackageManager call in composition, which runs on the UI thread.
                    model = remember(openTarget) { openTarget.toStub() },
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.setup_adb_open_manager_action, managerName))
            }
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
private fun RootActionsConnectionFailedPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.ROOT,
            state = RootSetupModule.Result(
                useRoot = true,
                isInstalled = true,
                // The su prompt was never answered, so the handshake ran out its budget.
                serviceState = RootServiceState.TimedOut,
            ),
            isRequired = true,
            priority = 5,
        ),
        onExecuteAction = {},
        switchLabel = "Use Root"
    )
}

private object PreviewInstallGuide : AdbManagerInstallGuide {
    override val backend: AdbBackend = AdbBackend.PORTER
    override val url: String = "https://porter.darken.eu/setup"
}

private val PREVIEW_PORTER = ShizukuSetupModule.Result(
    useShizuku = true,
    backend = AdbBackend.PORTER,
    pkg = "eu.darken.porter".toPkgId(),
    managerLabel = "Porter",
    isInstalled = true,
)

@Composable
private fun ShizukuActionsPreview(result: ShizukuSetupModule.Result) {
    CompositionLocalProvider(LocalAdbManagerInstallGuide provides PreviewInstallGuide) {
        RootShizukuActions(
            item = SetupItem(
                type = SetupModule.Type.SHIZUKU,
                state = result,
                isRequired = false,
                priority = 6,
            ),
            onExecuteAction = {},
            switchLabel = "Use ADB access if available",
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsDisabledPreview() = ShizukuActionsPreview(PREVIEW_PORTER.copy(useShizuku = null))

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsNotInstalledPreview() = ShizukuActionsPreview(
    ShizukuSetupModule.Result(useShizuku = true, isInstalled = false),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsButlerUpdateRequiredPreview() = ShizukuActionsPreview(
    PREVIEW_PORTER.copy(isCompatible = false, clientTooOld = true),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsManagerUpdateRequiredPreview() = ShizukuActionsPreview(
    PREVIEW_PORTER.copy(isCompatible = false, serverTooOld = true),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsConnectedPreview() = ShizukuActionsPreview(
    PREVIEW_PORTER.copy(
        permissionState = AdbPermissionState.Granted,
        basicService = true,
        serviceState = ShizukuServiceState.Available,
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsConnectedWithoutManagerPreview() = ShizukuActionsPreview(
    // The manager was uninstalled while its server keeps running.
    PREVIEW_PORTER.copy(
        pkg = null,
        managerLabel = null,
        permissionState = AdbPermissionState.Granted,
        basicService = true,
        serviceState = ShizukuServiceState.Available,
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsPermissionDeniedPreview() = ShizukuActionsPreview(
    PREVIEW_PORTER.copy(
        permissionState = AdbPermissionState.Denied(permanentlyDenied = false),
        basicService = true,
        serviceState = ShizukuServiceState.PermissionDenied,
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsConnectionFailedPreview() = ShizukuActionsPreview(
    // The server answers, but our user service never came up.
    PREVIEW_PORTER.copy(
        permissionState = AdbPermissionState.Granted,
        basicService = true,
        serviceState = ShizukuServiceState.TimedOut,
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsConnectingPreview() = ShizukuActionsPreview(
    PREVIEW_PORTER.copy(
        permissionState = AdbPermissionState.Granted,
        basicService = true,
        serviceState = ShizukuServiceState.NotChecked,
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsNotConnectedPreview() = ShizukuActionsPreview(PREVIEW_PORTER)
