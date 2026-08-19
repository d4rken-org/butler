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
import eu.darken.butler.common.flow.combine as combineMany
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.developer.core.DeveloperLogRepo
import eu.darken.butler.developer.core.DeveloperWorkspace
import eu.darken.butler.developer.core.operations.DeveloperCommand
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.core.operations.history.HistorySettings
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDao
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryPathEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryScopeEntity
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
    private val operationHistoryDao: OperationHistoryDao,
    private val historySettings: HistorySettings,
    chromeFactory: WorkspacePageChrome.Factory,
) : ViewModel4(dispatchers, logTag("Developer", "Workspace", id.shortTag, "Page")) {

    private val chrome = chromeFactory.create(id, vmScope)

    val shareIntentEvent = chrome.shareIntentEvent
    val operations = chrome.operations.asStateFlow()

    // The cancel confirmation is rendered by the overlay slot, which is a sibling of the page — a
    // `remember` in the page would be a different instance from the one the overlay reads.
    private val _cancelOperationConfirmation = MutableStateFlow<Operation.Id?>(null)
    val cancelOperationConfirmation: StateFlow<Operation.Id?> = _cancelOperationConfirmation

    fun requestCancelOperation(operationId: Operation.Id) {
        _cancelOperationConfirmation.value = operationId
    }

    fun dismissCancelOperationConfirmation() {
        _cancelOperationConfirmation.value = null
    }

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

    // Options state
    private val rootTestResult = MutableStateFlow<RootTestResult?>(null)
    private val isRootTesting = MutableStateFlow(false)
    private val shizukuTestResult = MutableStateFlow<ShizukuTestResult?>(null)
    private val isShizukuTesting = MutableStateFlow(false)

    private val logSource = combine(
        developerLogRepo.logLines,
        pausedLogSnapshot,
        isLogPaused,
    ) { liveLogs, snapshot, paused ->
        LogDisplay(
            lines = if (paused && snapshot != null) snapshot else liveLogs,
            isPaused = paused,
        )
    }

    private val testDataSource = combine(
        targetPaths,
        largeFilesEnabled,
        nestedStructureEnabled,
        textFilesEnabled,
    ) { paths, largeFiles, nested, text ->
        val pathInfos = paths.map { path ->
            TargetPathInfo(
                path = path,
                displayPath = path.userReadablePath.get(context),
            )
        }
        TestDataState(
            targetPaths = pathInfos,
            largeFilesEnabled = largeFiles,
            nestedStructureEnabled = nested,
            textFilesEnabled = text,
            canGenerate = pathInfos.isNotEmpty() && (largeFiles || nested || text),
        )
    }

    private val optionsSource = combineMany(
        debugSettings.isDebugMode.flow,
        debugSettings.isTraceMode.flow,
        debugSettings.floatingLogVisible.flow,
        rootTestResult,
        isRootTesting,
        shizukuTestResult,
        isShizukuTesting,
        developerSettings.isDeveloperModeUnlocked.flow,
    ) { isDebugMode, isTraceMode, isFloatingLog, rootResult, rootTesting, shizukuResult, shizukuTesting, isDeveloperModeUnlocked ->
        OptionsState(
            isDebugMode = isDebugMode,
            isTraceMode = isTraceMode,
            isFloatingLogEnabled = isFloatingLog,
            rootTestResult = rootResult,
            isRootTesting = rootTesting,
            shizukuTestResult = shizukuResult,
            isShizukuTesting = shizukuTesting,
            canHideDeveloperMode = isDeveloperModeUnlocked,
        )
    }

    // System info polls on its own cadence instead of riding the log tick, so log spam doesn't
    // re-run StatFs/ActivityManager queries; the sub-state split keeps the top-level combine cheap.
    private val systemInfoSource = flow {
        while (true) {
            emit(getSystemInfo())
            delay(5.seconds)
        }
    }.flowOn(dispatchers.IO)

    val state = combine(
        selectedTab,
        systemInfoSource,
        logSource,
        testDataSource,
        optionsSource,
    ) { tab, systemInfo, logDisplay, testDataState, optionsState ->
        State(
            id = id,
            selectedTab = tab,
            systemInfo = systemInfo,
            logLines = logDisplay.lines,
            isLogPaused = logDisplay.isPaused,
            testDataState = testDataState,
            optionsState = optionsState,
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
        // Log first: with the shared recorder capturing all tags, logging after the clear would
        // immediately repopulate the buffer with this very line.
        log(tag) { "Clearing logs" }
        developerLogRepo.clear()
        pausedLogSnapshot.value = null
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

    /**
     * Populates ~25 fake operation history rows for design iteration on the dev emulator. Inserts
     * directly via the DAO (bypasses [OperationsManager.completedOperations] subscription) so this
     * generator itself does not appear in History — only the synthetic rows do.
     */
    fun generateTestHistory() = launch {
        log(tag, INFO) { "generateTestHistory(): inserting sample entries" }
        withContext(dispatchers.IO + NonCancellable) {
            val maxItems = historySettings.maxHistoryItems.value()
            val now = Clock.System.now()
            val rng = kotlin.random.Random(System.currentTimeMillis())

            data class Spec(
                val ago: kotlin.time.Duration,
                val kind: Operation.Metadata.Kind,
                val outcome: HistoryOutcome,
                val intent: Operation.Metadata.Intent? = null,
                val origin: HistoryEntry.OriginType = HistoryEntry.OriginType.EXPLORER,
                val pathRoot: String = "/storage/emulated/0/DCIM",
                val fileNames: List<String> = listOf("photo.jpg"),
                val withPreviousPath: Boolean = false,
                val pathsTruncatedCount: Int? = null,
            )

            val specs = listOf(
                // Today (8)
                Spec(2.minutes, Operation.Metadata.Kind.COPY, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/DCIM", fileNames = listOf("IMG_2024.jpg", "IMG_2025.jpg")),
                Spec(7.minutes, Operation.Metadata.Kind.MOVE, HistoryOutcome.COMPLETED, intent = Operation.Metadata.Intent.RENAME, pathRoot = "/storage/emulated/0/Documents", fileNames = listOf("notes.txt"), withPreviousPath = true),
                Spec(15.minutes, Operation.Metadata.Kind.DELETE, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Download", fileNames = listOf("temp.bin")),
                Spec(45.minutes, Operation.Metadata.Kind.CREATE_FOLDER, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Pictures", fileNames = listOf("Vacation 2024")),
                Spec(1.hours + 30.minutes, Operation.Metadata.Kind.SAVE, HistoryOutcome.PARTIAL, pathRoot = "/storage/emulated/0/Documents", fileNames = listOf("draft.md", "outline.md", "todo.md")),
                Spec(3.hours, Operation.Metadata.Kind.DELETE, HistoryOutcome.FAILED, pathRoot = "/storage/emulated/0/Music", fileNames = listOf("locked.flac")),
                Spec(5.hours, Operation.Metadata.Kind.MOVE, HistoryOutcome.CANCELLED, intent = Operation.Metadata.Intent.PASTE_MOVE, pathRoot = "/storage/emulated/0/Download", fileNames = listOf("big.iso"), withPreviousPath = true),
                Spec(8.hours, Operation.Metadata.Kind.COPY, HistoryOutcome.COMPLETED, intent = Operation.Metadata.Intent.PASTE_COPY, pathRoot = "/storage/emulated/0/Pictures", fileNames = listOf("portrait.png", "landscape.png", "macro.png", "abstract.png")),
                // Yesterday (4)
                Spec(1.days + 2.hours, Operation.Metadata.Kind.CREATE_FILE, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Documents", fileNames = listOf("readme.txt")),
                Spec(1.days + 6.hours, Operation.Metadata.Kind.DELETE, HistoryOutcome.COMPLETED, origin = HistoryEntry.OriginType.SEARCHER, pathRoot = "/storage/emulated/0/Download", fileNames = (1..15).map { "old_$it.tmp" }),
                Spec(1.days + 9.hours, Operation.Metadata.Kind.SAVE, HistoryOutcome.COMPLETED, origin = HistoryEntry.OriginType.SAVER, pathRoot = "/storage/emulated/0/Documents", fileNames = listOf("export.pdf")),
                Spec(1.days + 14.hours, Operation.Metadata.Kind.COPY, HistoryOutcome.PARTIAL, pathRoot = "/storage/emulated/0/Music", fileNames = listOf("a.mp3", "b.mp3", "c.mp3", "d.mp3")),
                // This week (5)
                Spec(2.days + 1.hours, Operation.Metadata.Kind.MOVE, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Pictures/2024", fileNames = listOf("sunset.jpg"), withPreviousPath = true),
                Spec(3.days, Operation.Metadata.Kind.DELETE, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/DCIM/Camera", fileNames = (1..200).map { "deleted_photo_$it.jpg" }, pathsTruncatedCount = 1500),
                Spec(4.days + 5.hours, Operation.Metadata.Kind.CREATE_FOLDER, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Documents/Archive", fileNames = listOf("2024-Q4")),
                Spec(5.days, Operation.Metadata.Kind.COPY, HistoryOutcome.FAILED, pathRoot = "/storage/emulated/0/Music", fileNames = listOf("missing.flac")),
                Spec(6.days, Operation.Metadata.Kind.SAVE, HistoryOutcome.COMPLETED, origin = HistoryEntry.OriginType.SAVER, pathRoot = "/storage/emulated/0/Download", fileNames = listOf("backup.zip")),
                // This month (5)
                Spec(8.days, Operation.Metadata.Kind.DELETE, HistoryOutcome.CANCELLED, pathRoot = "/storage/emulated/0/Pictures", fileNames = listOf("temp.jpg")),
                Spec(12.days, Operation.Metadata.Kind.MOVE, HistoryOutcome.COMPLETED, intent = Operation.Metadata.Intent.RENAME, pathRoot = "/storage/emulated/0/Documents", fileNames = listOf("invoice.pdf"), withPreviousPath = true),
                Spec(18.days, Operation.Metadata.Kind.CREATE_FILE, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Documents/Notes", fileNames = listOf("ideas.md")),
                Spec(22.days, Operation.Metadata.Kind.COPY, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Pictures/Wallpapers", fileNames = listOf("nature.png", "geometric.png")),
                Spec(28.days, Operation.Metadata.Kind.SAVE, HistoryOutcome.PARTIAL, origin = HistoryEntry.OriginType.SAVER, pathRoot = "/storage/emulated/0/Download", fileNames = (1..6).map { "report_$it.pdf" }),
                // Older (3)
                Spec(35.days, Operation.Metadata.Kind.DELETE, HistoryOutcome.COMPLETED, pathRoot = "/storage/emulated/0/Music", fileNames = (1..8).map { "track_$it.mp3" }),
                Spec(60.days, Operation.Metadata.Kind.CREATE_FOLDER, HistoryOutcome.COMPLETED, origin = HistoryEntry.OriginType.DEVELOPER, pathRoot = "/storage/emulated/0/test", fileNames = listOf("scratch")),
                Spec(120.days, Operation.Metadata.Kind.COPY, HistoryOutcome.FAILED, pathRoot = "/storage/emulated/0/DCIM/Old", fileNames = listOf("ancient.jpg")),
            )

            for ((index, spec) in specs.withIndex()) {
                val rowId = Uuid.random().toString()
                val completedAt = now - spec.ago
                val startedAt = completedAt - 5.seconds
                val truncated = spec.pathsTruncatedCount != null
                val storedPaths = spec.fileNames.take(200)
                val pathEntities = storedPaths.mapIndexed { i, name ->
                    val fullPath = "${spec.pathRoot}/$name"
                    val change = when (spec.kind) {
                        Operation.Metadata.Kind.DELETE -> Operation.Report.PathChange.Change.REMOVED
                        Operation.Metadata.Kind.MOVE -> Operation.Report.PathChange.Change.MOVED
                        else -> Operation.Report.PathChange.Change.ADDED
                    }
                    OperationHistoryPathEntity(
                        operationHistoryId = rowId,
                        path = fullPath,
                        previousPath = if (spec.withPreviousPath) "/storage/emulated/0/legacy/${name}" else null,
                        change = change.name,
                        sortIndex = i,
                    )
                }

                val scopeEntities = (listOf(spec.pathRoot) + storedPaths.map { "${spec.pathRoot}/$it" })
                    .mapIndexed { i, path ->
                        OperationHistoryScopeEntity(
                            operationHistoryId = rowId,
                            path = path,
                            sortIndex = i,
                        )
                    }

                val outcomeLabel = spec.outcome.name.lowercase()
                val intentLabel = spec.intent?.name?.lowercase()?.replace('_', ' ')
                val titleText = if (intentLabel != null) "${spec.kind.name} ($intentLabel)" else spec.kind.name
                val descriptionText = "${spec.fileNames.size} item(s) under ${spec.pathRoot}"
                val errorMessage = when (spec.outcome) {
                    HistoryOutcome.FAILED -> "Synthetic failure for design iteration"
                    HistoryOutcome.PARTIAL -> null
                    else -> null
                }

                val entry = OperationHistoryEntity(
                    id = rowId,
                    kind = spec.kind.name,
                    intent = spec.intent?.name,
                    originType = spec.origin.name,
                    originWorkspaceId = "test-${index}",
                    title = titleText,
                    description = descriptionText,
                    summary = "${storedPaths.size} item(s) — $outcomeLabel",
                    startedAt = startedAt,
                    completedAt = completedAt,
                    durationMs = (completedAt - startedAt).inWholeMilliseconds.coerceAtLeast(0),
                    outcome = spec.outcome.name,
                    errorMessage = errorMessage,
                    errorClass = errorMessage?.let { "java.io.IOException" },
                    affectedPathsCount = spec.pathsTruncatedCount ?: storedPaths.size,
                    partialErrorCount = if (spec.outcome == HistoryOutcome.PARTIAL) rng.nextInt(1, 3) else 0,
                    pathsTruncated = truncated,
                    primaryPath = pathEntities.firstOrNull()?.path,
                )

                operationHistoryDao.insertWithPathsAndTrim(
                    entry = entry,
                    paths = pathEntities,
                    scopePaths = scopeEntities,
                    maxItems = maxItems,
                )
            }
            log(tag, INFO) { "generateTestHistory(): inserted ${specs.size} rows" }
        }
    }

    fun cancelOperation(operationId: Operation.Id) = chrome.cancelOperation(operationId)

    fun dismissOperation(operationId: Operation.Id) = chrome.dismissOperation(operationId)

    fun clearCompletedOperations() = chrome.clearCompletedOperations()

    fun shareOperationError(operationId: Operation.Id) = chrome.shareOperationError(operationId)

    fun toggleDebugMode(enabled: Boolean) {
        log(tag) { "Debug mode toggled: $enabled" }
        launch { debugSettings.isDebugMode.value(enabled) }
    }

    fun toggleTraceMode(enabled: Boolean) {
        log(tag) { "Trace mode toggled: $enabled" }
        launch { debugSettings.isTraceMode.value(enabled) }
    }

    fun toggleFloatingLog(enabled: Boolean) {
        log(tag) { "Floating log panel toggled: $enabled" }
        launch { debugSettings.floatingLogVisible.value(enabled) }
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

    private data class LogDisplay(
        val lines: List<String>,
        val isPaused: Boolean,
    )

    data class OptionsState(
        val isDebugMode: Boolean,
        val isTraceMode: Boolean,
        val isFloatingLogEnabled: Boolean,
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
