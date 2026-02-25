package eu.darken.butler.developer.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.developer.DeveloperSettings
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.flow.combine
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.common.flow.throttleLatest
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.developer.core.DeveloperLogRepo
import eu.darken.butler.developer.core.DeveloperWorkspace
import eu.darken.butler.developer.core.operations.DeveloperCommand
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.toOperationsDisplayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel(assistedFactory = DeveloperWorkspaceViewModel.Factory::class)
class DeveloperWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val developerLogRepo: DeveloperLogRepo,
    private val debugSettings: DebugSettings,
    private val developerSettings: DeveloperSettings,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val workspaceRemote: WorkspaceRemote,
    private val workspaceProvider: WorkspaceProvider,
    private val operationsManager: OperationsManager,
) : ViewModel4(dispatchers, logTag("Developer", "Workspace", id.shortTag, "Page")) {

    private val selectedTab = MutableStateFlow(DeveloperTab.SYSTEM)
    private val pausedLogSnapshot = MutableStateFlow<List<String>?>(null)
    private val isLogPaused = MutableStateFlow(false)

    // Test data state - Target paths
    private val targetPaths = MutableStateFlow<List<APath<*>>>(emptyList())

    // Test data state - Generate
    private val largeFilesEnabled = MutableStateFlow(false)
    private val nestedStructureEnabled = MutableStateFlow(false)
    private val textFilesEnabled = MutableStateFlow(true)

    // Workspace and operations
    private val workspaceSource = workspaceProvider.retrieve(id)
        .map { it as? DeveloperWorkspace }
        .filterNotNull()

    private val operationsState = workspaceSource
        .flatMapLatest { it.operations }
        .map { opsState -> opsState.operations }
        .toOperationsDisplayState()

    // Options state
    private val rootTestResult = MutableStateFlow<RootTestResult?>(null)
    private val isRootTesting = MutableStateFlow(false)
    private val shizukuTestResult = MutableStateFlow<ShizukuTestResult?>(null)
    private val isShizukuTesting = MutableStateFlow(false)

    val state = combine(
        selectedTab,
        developerLogRepo.logLines.throttleLatest(100.milliseconds),
        pausedLogSnapshot,
        isLogPaused,
        targetPaths,
        largeFilesEnabled,
        nestedStructureEnabled,
        textFilesEnabled,
        operationsState,
        debugSettings.isDebugMode.flow,
        debugSettings.isTraceMode.flow,
        rootTestResult,
        isRootTesting,
        shizukuTestResult,
        isShizukuTesting,
        developerSettings.isDeveloperModeUnlocked.flow,
    ) { tab, liveLogs, snapshot, paused, paths, largeFiles, nested, text,
        ops, isDebugMode, isTraceMode, rootResult,
        rootTesting, shizukuResult, shizukuTesting, isDeveloperModeUnlocked ->
        val displayLogs = if (paused && snapshot != null) snapshot else liveLogs
        val systemInfo = getSystemInfo()

        val pathInfos = paths.map { path ->
            TargetPathInfo(
                path = path,
                displayPath = path.userReadablePath.get(context),
            )
        }

        State(
            id = id,
            selectedTab = tab,
            systemInfo = systemInfo,
            logLines = displayLogs,
            isLogPaused = paused,
            testDataState = TestDataState(
                targetPaths = pathInfos,
                largeFilesEnabled = largeFiles,
                nestedStructureEnabled = nested,
                textFilesEnabled = text,
                canGenerate = pathInfos.isNotEmpty() && (largeFiles || nested || text),
            ),
            optionsState = OptionsState(
                isDebugMode = isDebugMode,
                isTraceMode = isTraceMode,
                rootTestResult = rootResult,
                isRootTesting = rootTesting,
                shizukuTestResult = shizukuResult,
                isShizukuTesting = shizukuTesting,
                canHideDeveloperMode = isDeveloperModeUnlocked,
            ),
            operationsState = ops,
        )
    }.asStateFlow()

    init {
        log(tag) { "Initialized for workspace $id" }

        // Pre-populate target paths with detected storage volumes
        launch {
            if (targetPaths.value.isEmpty()) {
                val volumes = withContext(dispatchers.IO) { getStorageVolumes() }
                targetPaths.value = volumes.map { LocalPath.build(it.path) }
                log(tag) { "Pre-populated ${volumes.size} storage volume(s)" }
            }
        }

        // Listen for picker results
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag, INFO) { "Received picker result: ${result.selectedPaths}" }
                if (result.selectedPaths.isNotEmpty()) {
                    targetPaths.value = (targetPaths.value + result.selectedPaths)
                        .distinctBy { it.path }
                }
            }
            .launchIn(vmScope)
    }

    fun selectTab(tab: DeveloperTab) {
        log(tag) { "Tab selected: $tab" }
        selectedTab.value = tab
    }

    fun toggleLogPause() {
        val newPaused = !isLogPaused.value
        if (newPaused) {
            // When pausing, take a snapshot of current logs
            pausedLogSnapshot.value = developerLogRepo.currentLogLines
        } else {
            // When resuming, clear the snapshot
            pausedLogSnapshot.value = null
        }
        isLogPaused.value = newPaused
        log(tag) { "Log viewing ${if (newPaused) "paused" else "resumed"}" }
    }

    fun clearLogs() {
        developerLogRepo.clear()
        pausedLogSnapshot.value = null
        log(tag) { "Logs cleared" }
    }

    fun openPathPicker() = launch {
        log(tag) { "Opening path picker" }
        workspaceRemote.launchPicker(
            callerWorkspaceId = id,
            startPath = null,
            selection = PickerConfig.Selection.DirectoryMulti,
            requireWritable = true,
        )
    }

    fun removePath(path: APath<*>) {
        targetPaths.value = targetPaths.value.filter { it.path != path.path }
        log(tag) { "Removed path: ${path.path}, remaining: ${targetPaths.value.size}" }
    }

    fun toggleLargeFiles(enabled: Boolean) {
        largeFilesEnabled.value = enabled
    }

    fun toggleNestedStructure(enabled: Boolean) {
        nestedStructureEnabled.value = enabled
    }

    fun toggleTextFiles(enabled: Boolean) {
        textFilesEnabled.value = enabled
    }

    fun generateTestData() {
        val paths = targetPaths.value
        if (paths.isEmpty()) {
            log(tag, WARN) { "No target paths selected" }
            return
        }

        log(tag) { "Starting test data generation for ${paths.size} path(s)" }

        launch {
            val workspace = workspaceSource.first()

            for (path in paths) {
                log(tag) { "Generating test data in: $path" }

                if (largeFilesEnabled.value) {
                    workspace.execute(DeveloperCommand.GenerateLargeFiles(path))
                }
                if (nestedStructureEnabled.value) {
                    workspace.execute(DeveloperCommand.GenerateNestedStructure(path))
                }
                if (textFilesEnabled.value) {
                    workspace.execute(DeveloperCommand.GenerateTextFiles(path))
                }
            }
        }
    }

    fun cancelOperation(operationId: Operation.Id) = launch {
        operationsManager.cancel(operationId)
    }

    fun dismissOperation(operationId: Operation.Id) = launch {
        operationsManager.remove(operationId)
    }

    fun clearCompletedOperations() = launch {
        operationsManager.clearCompleted()
    }

    fun toggleDebugMode(enabled: Boolean) {
        log(tag) { "Debug mode toggled: $enabled" }
        launch { debugSettings.isDebugMode.value(enabled) }
    }

    fun toggleTraceMode(enabled: Boolean) {
        log(tag) { "Trace mode toggled: $enabled" }
        launch { debugSettings.isTraceMode.value(enabled) }
    }

    fun hideDeveloperMode() = launch {
        log(tag, INFO) { "Hiding developer mode" }
        developerSettings.isDeveloperModeUnlocked.value(false)
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    fun testRoot() {
        if (isRootTesting.value) return
        log(tag) { "Starting root test" }
        isRootTesting.value = true
        rootTestResult.value = null

        launch {
            try {
                val isInstalled = rootManager.isInstalled()
                val isRooted = rootManager.isRooted()
                val baseCheck = if (isRooted) {
                    try {
                        rootManager.serviceClient.get().use { it.item.ipc.checkBase() }
                    } catch (e: Exception) {
                        log(tag, WARN) { "Root base check failed: ${e.asLog()}" }
                        null
                    }
                } else null

                rootTestResult.value = RootTestResult(
                    isInstalled = isInstalled,
                    isRooted = isRooted,
                    baseCheck = baseCheck,
                )
                log(tag) { "Root test completed: installed=$isInstalled, rooted=$isRooted, baseCheck=$baseCheck" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Root test failed: ${e.asLog()}" }
                rootTestResult.value = RootTestResult(
                    isInstalled = false,
                    isRooted = false,
                    baseCheck = null,
                )
            } finally {
                isRootTesting.value = false
            }
        }
    }

    fun testShizuku() {
        if (isShizukuTesting.value) return
        log(tag) { "Starting Shizuku test" }
        isShizukuTesting.value = true
        shizukuTestResult.value = null

        launch {
            try {
                val isInstalled = shizukuManager.isInstalled()
                val isGranted = shizukuManager.isGranted()
                val isCompatible = shizukuManager.isCompatible()
                val isServiceAvailable = if (isGranted == true) {
                    try {
                        shizukuManager.serviceClient.get().use { it.item.ipc.checkBase() != null }
                    } catch (e: Exception) {
                        log(tag, WARN) { "Shizuku service check failed: ${e.asLog()}" }
                        false
                    }
                } else false

                shizukuTestResult.value = ShizukuTestResult(
                    isInstalled = isInstalled,
                    isGranted = isGranted,
                    isCompatible = isCompatible,
                    isServiceAvailable = isServiceAvailable,
                )
                log(tag) { "Shizuku test completed: installed=$isInstalled, granted=$isGranted, compatible=$isCompatible, service=$isServiceAvailable" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Shizuku test failed: ${e.asLog()}" }
                shizukuTestResult.value = ShizukuTestResult(
                    isInstalled = false,
                    isGranted = null,
                    isCompatible = false,
                    isServiceAvailable = false,
                )
            } finally {
                isShizukuTesting.value = false
            }
        }
    }

    private fun getSystemInfo(): SystemInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val storageVolumes = getStorageVolumes()

        return SystemInfo(
            deviceModel = Build.MODEL,
            deviceManufacturer = Build.MANUFACTURER,
            apiLevel = Build.VERSION.SDK_INT,
            versionName = BuildConfigWrap.VERSION_NAME,
            versionCode = BuildConfigWrap.VERSION_CODE,
            flavor = BuildConfigWrap.FLAVOR.name,
            buildType = BuildConfigWrap.BUILD_TYPE.name,
            gitSha = BuildConfigWrap.GIT_SHA,
            memoryAvailable = formatFileSize(context, memoryInfo.availMem),
            memoryTotal = formatFileSize(context, memoryInfo.totalMem),
            storageVolumes = storageVolumes,
        )
    }

    private fun getStorageVolumes(): List<StorageVolumeInfo> {
        val volumes = mutableListOf<StorageVolumeInfo>()

        // Primary external storage
        val primaryPath = Environment.getExternalStorageDirectory()
        if (primaryPath.exists()) {
            val statFs = StatFs(primaryPath.absolutePath)
            volumes.add(
                StorageVolumeInfo(
                    name = "Internal Storage",
                    path = primaryPath.absolutePath,
                    freeSpace = formatFileSize(context, statFs.availableBlocksLong * statFs.blockSizeLong),
                    totalSpace = formatFileSize(context, statFs.blockCountLong * statFs.blockSizeLong),
                )
            )
        }

        // External storage directories (SD cards, USB, etc.)
        context.getExternalFilesDirs(null).forEachIndexed { index, file ->
            if (file != null && index > 0) {
                try {
                    val statFs = StatFs(file.absolutePath)
                    volumes.add(
                        StorageVolumeInfo(
                            name = "External Storage ${index}",
                            path = file.absolutePath,
                            freeSpace = formatFileSize(context, statFs.availableBlocksLong * statFs.blockSizeLong),
                            totalSpace = formatFileSize(context, statFs.blockCountLong * statFs.blockSizeLong),
                        )
                    )
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to get stats for ${file.absolutePath}: $e" }
                }
            }
        }

        return volumes
    }

    data class State(
        val id: Workspace.Id,
        val selectedTab: DeveloperTab,
        val systemInfo: SystemInfo,
        val logLines: List<String>,
        val isLogPaused: Boolean,
        val testDataState: TestDataState,
        val optionsState: OptionsState,
        val operationsState: OperationsDisplayState,
    )

    data class TestDataState(
        val targetPaths: List<TargetPathInfo>,
        val largeFilesEnabled: Boolean,
        val nestedStructureEnabled: Boolean,
        val textFilesEnabled: Boolean,
        val canGenerate: Boolean,
    )

    data class TargetPathInfo(
        val path: APath<*>,
        val displayPath: String,
    )

    data class SystemInfo(
        val deviceModel: String,
        val deviceManufacturer: String,
        val apiLevel: Int,
        val versionName: String,
        val versionCode: Long,
        val flavor: String,
        val buildType: String,
        val gitSha: String,
        val memoryAvailable: String,
        val memoryTotal: String,
        val storageVolumes: List<StorageVolumeInfo>,
    )

    data class StorageVolumeInfo(
        val name: String,
        val path: String,
        val freeSpace: String,
        val totalSpace: String,
    )

    enum class DeveloperTab {
        SYSTEM,
        OPTIONS,
        LOGS,
        TEST_DATA,
    }

    data class OptionsState(
        val isDebugMode: Boolean,
        val isTraceMode: Boolean,
        val rootTestResult: RootTestResult?,
        val isRootTesting: Boolean,
        val shizukuTestResult: ShizukuTestResult?,
        val isShizukuTesting: Boolean,
        val canHideDeveloperMode: Boolean,
    )

    data class RootTestResult(
        val isInstalled: Boolean,
        val isRooted: Boolean,
        val baseCheck: String?,
    )

    data class ShizukuTestResult(
        val isInstalled: Boolean,
        val isGranted: Boolean?,
        val isCompatible: Boolean,
        val isServiceAvailable: Boolean,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): DeveloperWorkspaceViewModel
    }
}
