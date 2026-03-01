package eu.darken.butler.main.ui.settings.support

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.RecorderManager
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.ui.settings.contactForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SupportScreenViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val webpageTool: WebpageTool,
    private val recorderManager: RecorderManager,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Support", "ViewModel")) {

    private val logFolderStats = MutableStateFlow<DebugLogFolderStats?>(null)

    val state = combine(
        recorderManager.state,
        logFolderStats,
    ) { recState, stats ->
        State(
            isRecording = recState.isRecording,
            logPath = recState.currentLogDir,
            debugLogFolderStats = stats,
        )
    }

    init {
        refreshDebugLogFolderStats()
    }

    fun debugLog() = launch {
        val currentState = recorderManager.state.map { it.isRecording }.first()
        if (currentState) {
            log(tag) { "Stopping debug log recording" }
            recorderManager.stopRecorder()
        } else {
            log(tag) { "Starting debug log recording" }
            recorderManager.startRecorder()
        }
    }

    fun openUrl(url: String) = launch {
        log(tag) { "Opening URL: $url" }
        webpageTool.open(url)
    }

    fun contactSupport() {
        navTo(Nav.Settings.contactForm())
    }

    fun refreshDebugLogFolderStats() = launch {
        log(tag) { "Refreshing debug log folder stats" }
        val dirs = recorderManager.getLogDirectories()
        var fileCount = 0
        var totalSize = 0L

        for (dir in dirs) {
            dir.listFiles()?.forEach { entry ->
                if (entry.isDirectory) {
                    fileCount++
                    totalSize += entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else if (entry.isFile && entry.extension == "zip") {
                    fileCount++
                    totalSize += entry.length()
                }
            }
        }

        logFolderStats.value = DebugLogFolderStats(fileCount = fileCount, totalSizeBytes = totalSize)
        log(tag) { "Debug log stats: $fileCount files, ${totalSize}B" }
    }

    fun deleteAllDebugLogs() = launch {
        log(tag) { "Deleting all debug logs" }
        val currentLogDir = recorderManager.state.first().currentLogDir
        val dirs = recorderManager.getLogDirectories()

        for (dir in dirs) {
            dir.listFiles()?.forEach { entry ->
                if (entry == currentLogDir) {
                    log(tag) { "Skipping active recording dir: $entry" }
                    return@forEach
                }
                try {
                    if (entry.isDirectory) {
                        entry.deleteRecursively()
                    } else {
                        entry.delete()
                    }
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to delete $entry: ${e.asLog()}" }
                }
            }
        }

        refreshDebugLogFolderStats()
    }

    data class DebugLogFolderStats(
        val fileCount: Int,
        val totalSizeBytes: Long,
    )

    data class State(
        val isRecording: Boolean,
        val logPath: File?,
        val debugLogFolderStats: DebugLogFolderStats? = null,
    )
}
