package eu.darken.butler.developer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import eu.darken.butler.developer.R
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.*
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun DeveloperWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: DeveloperWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { state ->
        DeveloperWorkspacePage(
            workspaceId = id,
            design = design,
            state = state,
            onTabSelected = { vm.selectTab(it) },
            onToggleLogPause = { vm.toggleLogPause() },
            onClearLogs = { vm.clearLogs() },
            onVolumeSelected = { vm.selectVolume(it) },
            onLargeFilesToggled = { vm.toggleLargeFiles(it) },
            onNestedStructureToggled = { vm.toggleNestedStructure(it) },
            onTextFilesToggled = { vm.toggleTextFiles(it) },
            onGenerateTestData = { vm.generateTestData() },
            onToggleDebugMode = { vm.toggleDebugMode(it) },
            onToggleTraceMode = { vm.toggleTraceMode(it) },
            onTestRoot = { vm.testRoot() },
            onTestShizuku = { vm.testShizuku() },
            onHideDeveloperMode = { vm.hideDeveloperMode() },
        )
    }
}

@Composable
fun DeveloperWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: State,
    onTabSelected: (DeveloperTab) -> Unit = {},
    onToggleLogPause: () -> Unit = {},
    onClearLogs: () -> Unit = {},
    onVolumeSelected: (Int) -> Unit = {},
    onLargeFilesToggled: (Boolean) -> Unit = {},
    onNestedStructureToggled: (Boolean) -> Unit = {},
    onTextFilesToggled: (Boolean) -> Unit = {},
    onGenerateTestData: () -> Unit = {},
    onToggleDebugMode: (Boolean) -> Unit = {},
    onToggleTraceMode: (Boolean) -> Unit = {},
    onTestRoot: () -> Unit = {},
    onTestShizuku: () -> Unit = {},
    onHideDeveloperMode: () -> Unit = {},
) {
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
                    DeveloperTab.entries.forEach { tab ->
                        FilterChip(
                            selected = state.selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            label = {
                                Text(
                                    text = when (tab) {
                                        DeveloperTab.SYSTEM -> stringResource(R.string.developer_tab_system)
                                        DeveloperTab.OPTIONS -> stringResource(R.string.developer_tab_options)
                                        DeveloperTab.LOGS -> stringResource(R.string.developer_tab_logs)
                                        DeveloperTab.TEST_DATA -> stringResource(R.string.developer_tab_testdata)
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
                        currentWorkspaceId = workspaceId,
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
                DeveloperTab.SYSTEM -> SystemInfoSection(state.systemInfo)
                DeveloperTab.OPTIONS -> OptionsSection(
                    optionsState = state.optionsState,
                    onToggleDebugMode = onToggleDebugMode,
                    onToggleTraceMode = onToggleTraceMode,
                    onTestRoot = onTestRoot,
                    onTestShizuku = onTestShizuku,
                    onHideDeveloperMode = onHideDeveloperMode,
                )
                DeveloperTab.LOGS -> LogsSection(
                    logs = state.logLines,
                    isPaused = state.isLogPaused,
                    onTogglePause = onToggleLogPause,
                    onClear = onClearLogs,
                )
                DeveloperTab.TEST_DATA -> TestDataSection(
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
private fun OptionsSection(
    optionsState: OptionsState,
    onToggleDebugMode: (Boolean) -> Unit,
    onToggleTraceMode: (Boolean) -> Unit,
    onTestRoot: () -> Unit,
    onTestShizuku: () -> Unit,
    onHideDeveloperMode: () -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Debug Options Card
        item {
            SectionCard(title = stringResource(R.string.developer_options_debug_header)) {
                DeveloperToggleRow(
                    title = stringResource(R.string.developer_options_debug_mode),
                    description = stringResource(R.string.developer_options_debug_mode_desc),
                    checked = optionsState.isDebugMode,
                    onCheckedChange = onToggleDebugMode,
                )
                DeveloperToggleRow(
                    title = stringResource(R.string.developer_options_trace_mode),
                    description = stringResource(R.string.developer_options_trace_mode_desc),
                    checked = optionsState.isTraceMode,
                    onCheckedChange = onToggleTraceMode,
                    enabled = optionsState.isDebugMode,
                )
            }
        }

        // Hide Developer Mode Card (only in release builds when unlocked)
        if (optionsState.canHideDeveloperMode) {
            item {
                SectionCard(title = stringResource(R.string.developer_options_visibility_header)) {
                    DeveloperActionRow(
                        title = stringResource(R.string.developer_options_hide_mode),
                        description = stringResource(R.string.developer_options_hide_mode_desc),
                        actionText = stringResource(R.string.developer_options_hide_action),
                        onClick = onHideDeveloperMode,
                    )
                }
            }
        }

        // Service Tests Card
        item {
            SectionCard(title = stringResource(R.string.developer_options_services_header)) {
                ServiceTestRow(
                    title = stringResource(R.string.developer_options_root_test_action),
                    isTesting = optionsState.isRootTesting,
                    onTest = onTestRoot,
                )
                optionsState.rootTestResult?.let { result ->
                    ServiceResultCard(
                        items = listOf(
                            stringResource(R.string.developer_options_root_installed_label) to result.isInstalled.toResultString(),
                            stringResource(R.string.developer_options_root_available_label) to result.isRooted.toResultString(),
                            stringResource(R.string.developer_options_root_base_check_label) to (result.baseCheck
                                ?: stringResource(R.string.developer_options_result_no)),
                        ),
                        isSuccess = result.isRooted,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                ServiceTestRow(
                    title = stringResource(R.string.developer_options_shizuku_test_action),
                    isTesting = optionsState.isShizukuTesting,
                    onTest = onTestShizuku,
                )
                optionsState.shizukuTestResult?.let { result ->
                    ServiceResultCard(
                        items = listOf(
                            stringResource(R.string.developer_options_shizuku_installed_label) to result.isInstalled.toResultString(),
                            stringResource(R.string.developer_options_shizuku_granted_label) to result.isGranted.toResultString(),
                            stringResource(R.string.developer_options_shizuku_compatible_label) to result.isCompatible.toResultString(),
                            stringResource(R.string.developer_options_shizuku_service_label) to result.isServiceAvailable.toResultString(),
                        ),
                        isSuccess = result.isServiceAvailable,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeveloperToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun DeveloperActionRow(
    title: String,
    description: String,
    actionText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onClick) {
            Text(text = actionText)
        }
    }
}

@Composable
private fun ServiceTestRow(
    title: String,
    isTesting: Boolean,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onTest,
            enabled = !isTesting,
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.developer_options_test_running))
            } else {
                Text(text = title)
            }
        }
    }
}

@Composable
private fun ServiceResultCard(
    items: List<Pair<String, String>>,
    isSuccess: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }.copy(alpha = 0.7f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Boolean.toResultString(): String = if (this) {
    stringResource(R.string.developer_options_result_yes)
} else {
    stringResource(R.string.developer_options_result_no)
}

@Composable
private fun Boolean?.toResultString(): String = when (this) {
    true -> stringResource(R.string.developer_options_result_yes)
    false -> stringResource(R.string.developer_options_result_no)
    null -> stringResource(R.string.developer_options_result_unknown)
}

@Composable
private fun SystemInfoSection(systemInfo: SystemInfo) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Device Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_device_header)) {
                InfoRow(label = stringResource(R.string.developer_system_device_model), value = systemInfo.deviceModel)
                InfoRow(
                    label = stringResource(R.string.developer_system_device_manufacturer),
                    value = systemInfo.deviceManufacturer
                )
                InfoRow(
                    label = stringResource(R.string.developer_system_device_api),
                    value = systemInfo.apiLevel.toString()
                )
            }
        }

        // Build Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_build_header)) {
                InfoRow(label = stringResource(R.string.developer_system_build_version), value = systemInfo.versionName)
                InfoRow(
                    label = stringResource(R.string.developer_system_build_code),
                    value = systemInfo.versionCode.toString()
                )
                InfoRow(label = stringResource(R.string.developer_system_build_flavor), value = systemInfo.flavor)
                InfoRow(label = stringResource(R.string.developer_system_build_type), value = systemInfo.buildType)
                InfoRow(label = stringResource(R.string.developer_system_build_git), value = systemInfo.gitSha)
            }
        }

        // Memory Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_memory_header)) {
                InfoRow(
                    label = stringResource(R.string.developer_system_memory_available),
                    value = systemInfo.memoryAvailable
                )
                InfoRow(label = stringResource(R.string.developer_system_memory_total), value = systemInfo.memoryTotal)
            }
        }

        // Storage Section
        item {
            SectionCard(title = stringResource(R.string.developer_system_storage_header)) {
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
                        stringResource(R.string.developer_logs_resume)
                    } else {
                        stringResource(R.string.developer_logs_pause)
                    }
                )
            }
            OutlinedButton(onClick = onClear) {
                Text(text = stringResource(R.string.developer_logs_clear))
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.developer_logs_empty),
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
    storageVolumes: List<StorageVolumeInfo>,
    testDataState: TestDataState,
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
            text = stringResource(R.string.developer_testdata_volume_label),
            style = MaterialTheme.typography.titleMedium,
        )

        // Volume selector
        if (storageVolumes.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.developer_testdata_no_storage),
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
            text = stringResource(R.string.developer_testdata_options_header),
            style = MaterialTheme.typography.titleMedium,
        )

        // Options
        TestDataOption(
            title = stringResource(R.string.developer_testdata_large_files),
            description = stringResource(R.string.developer_testdata_large_files_desc),
            checked = testDataState.largeFilesEnabled,
            onCheckedChange = onLargeFilesToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_nested_structure),
            description = stringResource(R.string.developer_testdata_nested_structure_desc),
            checked = testDataState.nestedStructureEnabled,
            onCheckedChange = onNestedStructureToggled,
            enabled = !testDataState.progress?.isGenerating.orFalse(),
        )
        TestDataOption(
            title = stringResource(R.string.developer_testdata_text_files),
            description = stringResource(R.string.developer_testdata_text_files_desc),
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
                    stringResource(R.string.developer_testdata_generating)
                } else {
                    stringResource(R.string.developer_testdata_generate)
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
private fun DeveloperWorkspacePagePreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        DeveloperWorkspacePage(
            workspaceId = workspaceId,
            state = State(
                id = workspaceId,
                selectedTab = DeveloperTab.SYSTEM,
                systemInfo = SystemInfo(
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
                        StorageVolumeInfo(
                            name = "Internal Storage",
                            path = "/storage/emulated/0",
                            freeSpace = "64 GB",
                            totalSpace = "128 GB",
                        )
                    ),
                ),
                logLines = emptyList(),
                isLogPaused = false,
                testDataState = TestDataState(
                    selectedVolumeIndex = 0,
                    largeFilesEnabled = false,
                    nestedStructureEnabled = false,
                    textFilesEnabled = true,
                    progress = null,
                    canGenerate = true,
                ),
                optionsState = OptionsState(
                    isDebugMode = true,
                    isTraceMode = false,
                    rootTestResult = null,
                    isRootTesting = false,
                    shizukuTestResult = null,
                    isShizukuTesting = false,
                    canHideDeveloperMode = false,
                ),
            ),
        )
    }
}
