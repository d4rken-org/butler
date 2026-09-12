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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.R
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.container.toStub
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.root.RootServiceState
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.shizuku.AdbManagerInstallGuide
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule

@Composable
fun RootShizukuActions(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit,
    switchLabel: String
) {
    val state = item.state as? SetupModule.State.Current
    val shizukuState = state as? ShizukuSetupModule.Result

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val managerLabel = shizukuState?.managerName

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
            SetupModule.Type.SHIZUKU -> {
                when {
                    // Ahead of the useShizuku guard on purpose: the setting defaults to null, so
                    // someone who installs a manager before ever touching the switch is exactly who
                    // needs to be told a restart is what makes it count.
                    shizukuState?.otherManagerInstalled == true -> stringResource(
                        R.string.setup_adb_restart_required,
                        shizukuState.restartRequiredLabel ?: shizukuState.backend.other.label,
                    )

                    shizukuState?.useShizuku != true -> null
                    !shizukuState.isInstalled -> stringResource(R.string.setup_status_not_installed)
                    !shizukuState.isCompatible -> stringResource(R.string.setup_status_unavailable)
                    // Two managers can be installed at once and only one of them is ever bound, so
                    // "Connected" alone leaves the user unable to tell which. Falls back to the bare
                    // wording when the label is missing, which is the Root card's case too.
                    shizukuState.ourService -> managerLabel?.let {
                        stringResource(R.string.setup_adb_status_connected_via, it)
                    } ?: stringResource(R.string.setup_status_connected)
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

        // The restart hint is the one enabled state without an action: the installed manager stays out
        // of reach until the process restarts, so neither opening nor installing anything is a remedy.
        if (shizukuState?.useShizuku == true && !shizukuState.otherManagerInstalled) {
            Spacer(modifier = Modifier.height(12.dp))

            AdbManagerAction(
                state = shizukuState,
                label = managerLabel,
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

/**
 * Display name of the manager backing [state], null while none is installed.
 *
 * Resolution belongs to the module, which does it off the UI thread and absorbs a failing lookup.
 * All that is left here is the backend's product name for when it came back with nothing: a package
 * id is not a name to show anybody.
 */
private val ShizukuSetupModule.Result.managerName: String?
    get() = takeIf { it.isInstalled }?.let { it.managerLabel ?: it.backend.label }

@Composable
private fun AdbManagerAction(
    modifier: Modifier = Modifier,
    state: ShizukuSetupModule.Result,
    label: String?,
    onExecuteAction: (SetupAction) -> Unit,
) {
    if (state.isInstalled) {
        OutlinedButton(
            modifier = modifier,
            onClick = { onExecuteAction(SetupAction.OpenAdbManager(state.pkg)) },
        ) {
            AsyncImage(
                // Through Coil rather than a PackageManager call in composition: AppIconFetcher does
                // the lookup off the UI thread behind the IPC funnel and substitutes a default icon
                // when there is nothing to load.
                model = remember(state.pkg) { state.pkg.toStub() },
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(text = stringResource(R.string.setup_adb_open_manager_action, label ?: state.backend.label))
        }
    } else {
        val guide = rememberAdbManagerInstallGuide()
        val label = guide?.let { stringResource(it.labelRes) } ?: state.backend.label

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
            Text(text = stringResource(R.string.setup_adb_install_manager_action, label))
        }
    }
}

@Composable
private fun rememberAdbManagerInstallGuide(): AdbManagerInstallGuide? {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, AdbManagerInstallGuideEntryPoint::class.java)
                .adbManagerInstallGuide()
        }.getOrNull()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AdbManagerInstallGuideEntryPoint {
    fun adbManagerInstallGuide(): AdbManagerInstallGuide
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
                managerLabel = "Shizuku",
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
private fun ShizukuActionsConnectedPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.SHIZUKU,
            state = ShizukuSetupModule.Result(
                pkg = "eu.darken.porter".toPkgId(),
                useShizuku = true,
                isCompatible = true,
                isInstalled = true,
                managerLabel = "Porter",
                basicService = true,
                serviceState = ShizukuServiceState.Available,
                alsoHasRoot = false,
            ),
            isRequired = false,
            priority = 6,
        ),
        onExecuteAction = {},
        switchLabel = "Use ADB access",
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
                managerLabel = "Shizuku",
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsRestartRequiredUnsetPreview() = ShizukuRestartRequiredPreview(useShizuku = null)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsRestartRequiredDisabledPreview() = ShizukuRestartRequiredPreview(useShizuku = false)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsRestartRequiredEnabledPreview() = ShizukuRestartRequiredPreview(useShizuku = true)

@Composable
private fun ShizukuRestartRequiredPreview(useShizuku: Boolean?) {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.SHIZUKU,
            state = ShizukuSetupModule.Result(
                pkg = "eu.darken.porter".toPkgId(),
                useShizuku = useShizuku,
                isCompatible = true,
                // The manager on the device belongs to the backend this process did not latch onto.
                isInstalled = false,
                restartRequiredFor = "moe.shizuku.privileged.api".toPkgId(),
                restartRequiredLabel = "Shizuku",
                alsoHasRoot = false,
            ),
            isRequired = false,
            priority = 6,
        ),
        onExecuteAction = {},
        switchLabel = "Use ADB access",
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShizukuActionsNoManagerPreview() {
    RootShizukuActions(
        item = SetupItem(
            type = SetupModule.Type.SHIZUKU,
            state = ShizukuSetupModule.Result(
                pkg = "eu.darken.porter".toPkgId(),
                useShizuku = true,
                isCompatible = true,
                isInstalled = false,
                serviceState = ShizukuServiceState.NotChecked,
                alsoHasRoot = false,
            ),
            isRequired = false,
            priority = 6,
        ),
        onExecuteAction = {},
        switchLabel = "Use ADB access",
    )
}
