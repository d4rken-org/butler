package eu.darken.butler.main.ui.settings.support

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.ui.settings.contactForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SupportScreenViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val webpageTool: WebpageTool,
    private val sessionManager: DebugSessionManager,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Support", "ViewModel")) {

    val state = sessionManager.state.map { sessState ->
        State(
            isRecording = sessState.activeSession != null,
            logPath = sessState.activeSession?.logDir,
            debugLogFolderStats = DebugLogFolderStats(
                fileCount = sessState.completedSessions.size + sessState.failedSessions.size,
                totalSizeBytes = sessState.completedSessions.sumOf { it.zipSize }
                    + sessState.failedSessions.sumOf { it.dirSize },
            ),
        )
    }

    fun debugLog() = launch {
        val isRecording = sessionManager.state.map { it.activeSession != null }.first()
        if (isRecording) {
            log(tag) { "Stopping debug log recording" }
            sessionManager.stopRecording()
        } else {
            log(tag) { "Starting debug log recording" }
            sessionManager.startRecording()
        }
    }

    fun openUrl(url: String) = launch {
        log(tag) { "Opening URL: $url" }
        webpageTool.open(url)
    }

    fun contactSupport() {
        navTo(Nav.Settings.contactForm())
    }

    fun refreshSessions() = launch {
        sessionManager.refreshSessions()
    }

    fun deleteAllDebugLogs() = launch {
        log(tag) { "Deleting all debug logs" }
        sessionManager.deleteAllSessions()
    }

    data class DebugLogFolderStats(
        val fileCount: Int,
        val totalSizeBytes: Long,
    )

    data class State(
        val isRecording: Boolean,
        val logPath: File?,
        val debugLogFolderStats: DebugLogFolderStats = DebugLogFolderStats(0, 0L),
    )
}
