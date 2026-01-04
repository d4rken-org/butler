package eu.darken.butler.sdmaid.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CleaningServices
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.Update
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.sdmaid.R
import eu.darken.butler.sdmaid.core.SdMaidWorkspace
import eu.darken.butler.sdmaid.core.arguments.SdMaidArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun SdMaidWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SdMaidWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SdMaidWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    SdMaidWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        onInstallClick = vm::openInstallPage,
        onToolSelect = vm::selectTool,
        onRetry = vm::retry,
    )
}

@Composable
fun SdMaidWorkspacePage(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<SdMaidWorkspaceViewModel.State>,
    onInstallClick: () -> Unit,
    onToolSelect: (SdMaidArguments.ToolType?) -> Unit,
    onRetry: () -> Unit,
) {
    val state by stateSource.collectAsState(initial = SdMaidWorkspaceViewModel.State())

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val connectionState = state.connectionState) {
            is SdMaidWorkspace.ConnectionState.Checking -> {
                LoadingContent()
            }

            is SdMaidWorkspace.ConnectionState.NotInstalled -> {
                InstallPromptContent(
                    onInstallClick = onInstallClick,
                )
            }

            is SdMaidWorkspace.ConnectionState.ServiceUnavailable -> {
                ServiceUnavailableContent()
            }

            is SdMaidWorkspace.ConnectionState.Connecting -> {
                LoadingContent(message = stringResource(R.string.sdmaid_status_connecting))
            }

            is SdMaidWorkspace.ConnectionState.Connected -> {
                DashboardContent(
                    version = connectionState.version,
                    currentTool = state.currentTool,
                    onToolSelect = onToolSelect,
                )
            }

            is SdMaidWorkspace.ConnectionState.Error -> {
                ErrorContent(
                    error = connectionState.error,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstallPromptContent(
    modifier: Modifier = Modifier,
    onInstallClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.TwoTone.CleaningServices,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.sdmaid_install_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.sdmaid_install_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onInstallClick) {
            Icon(
                imageVector = Icons.TwoTone.Download,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.sdmaid_install_action))
        }
    }
}

@Composable
private fun ServiceUnavailableContent(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.TwoTone.Update,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.sdmaid_service_unavailable_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.sdmaid_service_unavailable_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashboardContent(
    modifier: Modifier = Modifier,
    version: String,
    currentTool: SdMaidArguments.ToolType?,
    onToolSelect: (SdMaidArguments.ToolType?) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Connected to SD Maid SE v$version",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            Text(
                text = "IPC service integration coming soon. This workspace will allow you to run SD Maid tools directly from Butler.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Tool cards (placeholder for now)
        item {
            ToolCard(
                title = stringResource(R.string.sdmaid_tool_appcleaner),
                description = stringResource(R.string.sdmaid_tool_appcleaner_description),
                onClick = { onToolSelect(SdMaidArguments.ToolType.APP_CLEANER) },
            )
        }

        item {
            ToolCard(
                title = stringResource(R.string.sdmaid_tool_systemcleaner),
                description = stringResource(R.string.sdmaid_tool_systemcleaner_description),
                onClick = { onToolSelect(SdMaidArguments.ToolType.SYSTEM_CLEANER) },
            )
        }

        item {
            ToolCard(
                title = stringResource(R.string.sdmaid_tool_corpsefinder),
                description = stringResource(R.string.sdmaid_tool_corpsefinder_description),
                onClick = { onToolSelect(SdMaidArguments.ToolType.CORPSE_FINDER) },
            )
        }

        item {
            ToolCard(
                title = stringResource(R.string.sdmaid_tool_storageanalyzer),
                description = stringResource(R.string.sdmaid_tool_storageanalyzer_description),
                onClick = { onToolSelect(SdMaidArguments.ToolType.STORAGE_ANALYZER) },
            )
        }
    }
}

@Composable
private fun ToolCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorContent(
    modifier: Modifier = Modifier,
    error: Throwable,
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.TwoTone.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.sdmaid_status_error),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = error.message ?: "Unknown error",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRetry) {
            Text(stringResource(R.string.sdmaid_error_retry_action))
        }
    }
}

@Preview2
@Composable
private fun SdMaidWorkspacePagePreview_NotInstalled() {
    PreviewWrapper {
        InstallPromptContent(
            onInstallClick = {},
        )
    }
}

@Preview2
@Composable
private fun SdMaidWorkspacePagePreview_ServiceUnavailable() {
    PreviewWrapper {
        ServiceUnavailableContent()
    }
}

@Preview2
@Composable
private fun SdMaidWorkspacePagePreview_Connected() {
    PreviewWrapper {
        DashboardContent(
            version = "1.2.3",
            currentTool = null,
            onToolSelect = {},
        )
    }
}

@Preview2
@Composable
private fun SdMaidWorkspacePagePreview_Error() {
    PreviewWrapper {
        ErrorContent(
            error = Exception("Connection failed"),
            onRetry = {},
        )
    }
}
