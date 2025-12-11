package eu.darken.butler.debug.ui

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
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.throttleLatest
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.debug.core.DebugLogRepo
import eu.darken.butler.debug.core.testdata.TestDataGenerator
import eu.darken.butler.workspace.core.Workspace
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import eu.darken.butler.common.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = DebugWorkspaceViewModel.Factory::class)
class DebugWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    @ApplicationContext private val context: Context,
    private val debugLogRepo: DebugLogRepo,
    private val testDataGenerator: TestDataGenerator,
) : ViewModel4(dispatchers, logTag("Debug", "Workspace", id.shortTag, "Page"), navCtrl) {

    private val selectedTab = MutableStateFlow(DebugTab.SYSTEM)
    private val pausedLogSnapshot = MutableStateFlow<List<String>?>(null)
    private val isLogPaused = MutableStateFlow(false)

    // Test data state
    private val selectedVolumeIndex = MutableStateFlow(0)
    private val largeFilesEnabled = MutableStateFlow(false)
    private val nestedStructureEnabled = MutableStateFlow(false)
    private val textFilesEnabled = MutableStateFlow(true)
    private val generationProgress = MutableStateFlow<TestDataProgress?>(null)

    val state = combine(
        selectedTab,
        debugLogRepo.logLines.throttleLatest(100.milliseconds),
        pausedLogSnapshot,
        isLogPaused,
        selectedVolumeIndex,
        largeFilesEnabled,
        nestedStructureEnabled,
        textFilesEnabled,
        generationProgress,
    ) { tab, liveLogs, snapshot, paused, volIndex, largeFiles, nested, text, progress ->
        val displayLogs = if (paused && snapshot != null) snapshot else liveLogs
        val systemInfo = getSystemInfo()

        State(
            id = id,
            selectedTab = tab,
            systemInfo = systemInfo,
            logLines = displayLogs,
            isLogPaused = paused,
            testDataState = TestDataState(
                selectedVolumeIndex = volIndex,
                largeFilesEnabled = largeFiles,
                nestedStructureEnabled = nested,
                textFilesEnabled = text,
                progress = progress,
                canGenerate = systemInfo.storageVolumes.isNotEmpty() && (largeFiles || nested || text),
            ),
        )
    }.asStateFlow()

    init {
        log(tag) { "Initialized for workspace $id" }
    }

    fun selectTab(tab: DebugTab) {
        log(tag) { "Tab selected: $tab" }
        selectedTab.value = tab
    }

    fun toggleLogPause() {
        val newPaused = !isLogPaused.value
        if (newPaused) {
            // When pausing, take a snapshot of current logs
            pausedLogSnapshot.value = debugLogRepo.currentLogLines
        } else {
            // When resuming, clear the snapshot
            pausedLogSnapshot.value = null
        }
        isLogPaused.value = newPaused
        log(tag) { "Log viewing ${if (newPaused) "paused" else "resumed"}" }
    }

    fun clearLogs() {
        debugLogRepo.clear()
        pausedLogSnapshot.value = null
        log(tag) { "Logs cleared" }
    }

    fun selectVolume(index: Int) {
        selectedVolumeIndex.value = index
        log(tag) { "Selected volume index: $index" }
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
        val volumes = getStorageVolumes()
        if (volumes.isEmpty()) {
            log(tag, Logging.Priority.WARN) { "No storage volumes available" }
            return
        }

        val volumeIndex = selectedVolumeIndex.value.coerceIn(0, volumes.lastIndex)
        val baseDir = File(volumes[volumeIndex].path)

        log(tag) { "Starting test data generation in: $baseDir" }
        generationProgress.value = TestDataProgress(isGenerating = true, message = "Starting…")

        launch {
            var totalFilesCreated = 0

            if (largeFilesEnabled.value) {
                testDataGenerator.generateLargeFiles(baseDir).collect { progress ->
                    when (progress) {
                        is TestDataGenerator.Progress.Creating -> {
                            generationProgress.value = TestDataProgress(
                                isGenerating = true,
                                message = "Large files: ${progress.current}/${progress.total} - ${progress.name}",
                            )
                        }
                        is TestDataGenerator.Progress.Completed -> {
                            totalFilesCreated += progress.filesCreated
                            log(tag) { "Large files completed: ${progress.filesCreated} files" }
                        }
                        is TestDataGenerator.Progress.Error -> {
                            log(tag, Logging.Priority.ERROR) { "Large files error: ${progress.message}" }
                            generationProgress.value = TestDataProgress(
                                isGenerating = false,
                                message = "Error: ${progress.message}",
                            )
                            return@collect
                        }
                    }
                }
            }

            if (nestedStructureEnabled.value) {
                testDataGenerator.generateNestedStructure(baseDir).collect { progress ->
                    when (progress) {
                        is TestDataGenerator.Progress.Creating -> {
                            generationProgress.value = TestDataProgress(
                                isGenerating = true,
                                message = "Nested: ${progress.current}/${progress.total} - ${progress.name}",
                            )
                        }
                        is TestDataGenerator.Progress.Completed -> {
                            totalFilesCreated += progress.filesCreated
                            log(tag) { "Nested structure completed: ${progress.filesCreated} files" }
                        }
                        is TestDataGenerator.Progress.Error -> {
                            log(tag, Logging.Priority.ERROR) { "Nested structure error: ${progress.message}" }
                            generationProgress.value = TestDataProgress(
                                isGenerating = false,
                                message = "Error: ${progress.message}",
                            )
                            return@collect
                        }
                    }
                }
            }

            if (textFilesEnabled.value) {
                testDataGenerator.generateTextFiles(baseDir).collect { progress ->
                    when (progress) {
                        is TestDataGenerator.Progress.Creating -> {
                            generationProgress.value = TestDataProgress(
                                isGenerating = true,
                                message = "Text files: ${progress.current}/${progress.total} - ${progress.name}",
                            )
                        }
                        is TestDataGenerator.Progress.Completed -> {
                            totalFilesCreated += progress.filesCreated
                            log(tag) { "Text files completed: ${progress.filesCreated} files" }
                        }
                        is TestDataGenerator.Progress.Error -> {
                            log(tag, Logging.Priority.ERROR) { "Text files error: ${progress.message}" }
                            generationProgress.value = TestDataProgress(
                                isGenerating = false,
                                message = "Error: ${progress.message}",
                            )
                            return@collect
                        }
                    }
                }
            }

            generationProgress.value = TestDataProgress(
                isGenerating = false,
                message = "Completed! $totalFilesCreated files created",
            )
            log(tag) { "Test data generation completed: $totalFilesCreated total files" }
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
                    log(tag, Logging.Priority.WARN) { "Failed to get stats for ${file.absolutePath}: $e" }
                }
            }
        }

        return volumes
    }

    data class State(
        val id: Workspace.Id,
        val selectedTab: DebugTab,
        val systemInfo: SystemInfo,
        val logLines: List<String>,
        val isLogPaused: Boolean,
        val testDataState: TestDataState,
    )

    data class TestDataState(
        val selectedVolumeIndex: Int,
        val largeFilesEnabled: Boolean,
        val nestedStructureEnabled: Boolean,
        val textFilesEnabled: Boolean,
        val progress: TestDataProgress?,
        val canGenerate: Boolean,
    )

    data class TestDataProgress(
        val isGenerating: Boolean,
        val message: String,
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

    enum class DebugTab {
        SYSTEM,
        LOGS,
        TEST_DATA,
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): DebugWorkspaceViewModel
    }
}
