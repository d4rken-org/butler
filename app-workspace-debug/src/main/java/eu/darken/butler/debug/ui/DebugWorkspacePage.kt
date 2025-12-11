package eu.darken.butler.debug.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.debug.R
import eu.darken.butler.debug.ui.DebugWorkspaceViewModel.DebugTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun DebugWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: DebugWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: DebugWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { state ->
        DebugWorkspacePage(
            workspaceId = id,
            design = design,
            state = state,
            workspaceStateSource = workspaceButtonVm.state,
            workspaceActionHandler = workspaceButtonVm,
            onTabSelected = { vm.selectTab(it) },
            onToggleLogPause = { vm.toggleLogPause() },
            onClearLogs = { vm.clearLogs() },
            onVolumeSelected = { vm.selectVolume(it) },
            onLargeFilesToggled = { vm.toggleLargeFiles(it) },
            onNestedStructureToggled = { vm.toggleNestedStructure(it) },
            onTextFilesToggled = { vm.toggleTextFiles(it) },
            onGenerateTestData = { vm.generateTestData() },
        )
    }
}

@Composable
fun DebugWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: DebugWorkspaceViewModel.State,
    workspaceStateSource: Flow<WorkspaceButtonViewModel.State?>,
    workspaceActionHandler: WorkspaceActionHandler?,
    onTabSelected: (DebugTab) -> Unit = {},
    onToggleLogPause: () -> Unit = {},
    onClearLogs: () -> Unit = {},
    onVolumeSelected: (Int) -> Unit = {},
    onLargeFilesToggled: (Boolean) -> Unit = {},
    onNestedStructureToggled: (Boolean) -> Unit = {},
    onTextFilesToggled: (Boolean) -> Unit = {},
    onGenerateTestData: () -> Unit = {},
) {
    val workspaceButtonState by workspaceStateSource.collectAsState(null)

    Column(modifier = Modifier.fillMaxSize()) {
        // Floating header card with tabs and workspace button
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tab chips in a horizontally scrollable row
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DebugTab.entries.forEach { tab ->
                        FilterChip(
                            selected = state.selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            label = {
                                Text(
                                    text = when (tab) {
                                        DebugTab.SYSTEM -> stringResource(R.string.debug_tab_system)
                                        DebugTab.LOGS -> stringResource(R.string.debug_tab_logs)
                                        DebugTab.TEST_DATA -> stringResource(R.string.debug_tab_testdata)
                                    }
                                )
                            }
                        )
                    }
                }

                // Workspace button (only in single pane mode)
                if (design.isSingle) {
                    Spacer(modifier = Modifier.width(8.dp))
                    WorkspaceButton(
                        buttonSize = 40.dp,
                        state = workspaceButtonState,
                        currentWorkspaceId = workspaceId,
                        workspaceActionHandler = workspaceActionHandler,
                    )
                }
            }
        }

        // Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            when (state.selectedTab) {
                DebugTab.SYSTEM -> SystemInfoSection(state.systemInfo)
                DebugTab.LOGS -> LogsSection(
                    logs = state.logLines,
                    isPaused = state.isLogPaused,
                    onTogglePause = onToggleLogPause,
                    onClear = onClearLogs,
                )
                DebugTab.TEST_DATA -> TestDataSection(
                    storageVolumes = state.systemInfo.storageVolumes,
                    testDataState = state.testDataState,
                    onVolumeSelected = onVolumeSelected,
                    onLargeFilesToggled = onLargeFilesToggled,
                    onNestedStructureToggled = onNestedStructureToggled,
                    onTextFilesToggled = onTextFilesToggled,
                    onGenerateTestData = onGenerateTestData,
                )
            }
        }
    }
}

@Composable
private fun SystemInfoSection(systemInfo: DebugWorkspaceViewModel.SystemInfo) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Device Section
        item {
            SectionCard(title = stringResource(R.string.debug_system_device_header)) {
                InfoRow(label = stringResource(R.string.debug_system_device_model), value = systemInfo.deviceModel)
                InfoRow(label = stringResource(R.string.debug_system_device_manufacturer), value = systemInfo.deviceManufacturer)
                InfoRow(label = stringResource(R.string.debug_system_device_api), value = systemInfo.apiLevel.toString())
            }
        }

        // Build Section
        item {
            SectionCard(title = stringResource(R.string.debug_system_build_header)) {
                InfoRow(label = stringResource(R.string.debug_system_build_version), value = systemInfo.versionName)
                InfoRow(label = stringResource(R.string.debug_system_build_code), value = systemInfo.versionCode.toString())
                InfoRow(label = stringResource(R.string.debug_system_build_flavor), value = systemInfo.flavor)
                InfoRow(label = stringResource(R.string.debug_system_build_type), value = systemInfo.buildType)
                InfoRow(label = stringResource(R.string.debug_system_build_git), value = systemInfo.gitSha)
            }
        }

        // Memory Section
        item {
            SectionCard(title = stringResource(R.string.debug_system_memory_header)) {
                InfoRow(label = stringResource(R.string.debug_system_memory_available), value = systemInfo.memoryAvailable)
                InfoRow(label = stringResource(R.string.debug_system_memory_total), value = systemInfo.memoryTotal)
            }
        }

        // Storage Section
        item {
            SectionCard(title = stringResource(R.string.debug_system_storage_header)) {
                systemInfo.storageVolumes.forEach { volume ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = volume.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = volume.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            text = "${volume.freeSpace} free of ${volume.totalSpace}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsSection(
    logs: List<String>,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onTogglePause) {
                Text(
                    text = if (isPaused) {
                        stringResource(R.string.debug_logs_resume)
                    } else {
                        stringResource(R.string.debug_logs_pause)
                    }
                )
            }
            OutlinedButton(onClick = onClear) {
                Text(text = stringResource(R.string.debug_logs_clear))
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.debug_logs_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            val horizontalScrollState = rememberScrollState()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState),
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TestDataSection(
    storageVolumes: List<DebugWorkspaceViewModel.StorageVolumeInfo>,
    testDataState: DebugWorkspaceViewModel.TestDataState,
    onVolumeSelected: (Int) -> Unit,
    onLargeFilesToggled: (Boolean) -> Unit,
    onNestedStructureToggled: (Boolean) -> Unit,
    onTextFilesToggled: (Boolean) -> Unit,
    onGenerateTestData: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.debug_testdata_volume_label),
            style = MaterialTheme.typography.titleMedium,
        )

        // Volume selector
        if (storageVolumes.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.debug_testdata_no_storage),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                storageVolumes.forEachIndexed { index, volume ->
                    FilterChip(
                        selected = testDataState.selectedVolumeIndex == index,
                        onClick = { onVolumeSelected(index) },
                        label = {
                            Column {
                                Text(text = volume.name, style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text = "${volume.freeSpace} free",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.debug_testdata_options_header),
            style = MaterialTheme.typography.titleMedium,
        )

        // Options
        TestDataOption(
            title = stringResource(R.string.debug_testdata_large_files),
            description = stringResource(R.string.debug_testdata_large_files_desc),
            checked = testDataState.largeFilesEnabled,
            onCheckedChange = onLargeFilesToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )
        TestDataOption(
            title = stringResource(R.string.debug_testdata_nested_structure),
            description = stringResource(R.string.debug_testdata_nested_structure_desc),
            checked = testDataState.nestedStructureEnabled,
            onCheckedChange = onNestedStructureToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )
        TestDataOption(
            title = stringResource(R.string.debug_testdata_text_files),
            description = stringResource(R.string.debug_testdata_text_files_desc),
            checked = testDataState.textFilesEnabled,
            onCheckedChange = onTextFilesToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )

        // Progress indicator
        testDataState.progress?.let { progress ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (progress.isGenerating) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (progress.isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = progress.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Button(
            onClick = onGenerateTestData,
            modifier = Modifier.fillMaxWidth(),
            enabled = testDataState.canGenerate && !testDataState.progress?.isGenerating.orFalse(),
        ) {
            Text(
                text = if (testDataState.progress?.isGenerating == true) {
                    stringResource(R.string.debug_testdata_generating)
                } else {
                    stringResource(R.string.debug_testdata_generate)
                }
            )
        }
    }
}

private fun Boolean?.orFalse(): Boolean = this ?: false

@Composable
private fun TestDataOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.4f),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview2
@Composable
private fun DebugWorkspacePagePreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        DebugWorkspacePage(
            workspaceId = workspaceId,
            state = DebugWorkspaceViewModel.State(
                id = workspaceId,
                selectedTab = DebugTab.SYSTEM,
                systemInfo = DebugWorkspaceViewModel.SystemInfo(
                    deviceModel = "Pixel 8 Pro",
                    deviceManufacturer = "Google",
                    apiLevel = 34,
                    versionName = "1.0.0-dev",
                    versionCode = 100,
                    flavor = "FOSS",
                    buildType = "DEV",
                    gitSha = "abc123",
                    memoryAvailable = "4.2 GB",
                    memoryTotal = "8.0 GB",
                    storageVolumes = listOf(
                        DebugWorkspaceViewModel.StorageVolumeInfo(
                            name = "Internal Storage",
                            path = "/storage/emulated/0",
                            freeSpace = "64 GB",
                            totalSpace = "128 GB",
                        )
                    ),
                ),
                logLines = emptyList(),
                isLogPaused = false,
                testDataState = DebugWorkspaceViewModel.TestDataState(
                    selectedVolumeIndex = 0,
                    largeFilesEnabled = false,
                    nestedStructureEnabled = false,
                    textFilesEnabled = true,
                    progress = null,
                    canGenerate = true,
                ),
            ),
            workspaceStateSource = flowOf(
                WorkspaceButtonViewModel.State(
                    workspaceCount = 2,
                    operationsCount = 0,
                    attentionCount = 0,
                )
            ),
            workspaceActionHandler = null,
        )
    }
}
