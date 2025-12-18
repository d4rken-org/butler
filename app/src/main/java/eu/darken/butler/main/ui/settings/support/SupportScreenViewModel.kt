package eu.darken.butler.main.ui.settings.support

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.RecorderManager
import eu.darken.butler.common.ui.ViewModel4
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

    val state = recorderManager.state.map { recState ->
        State(
            isRecording = recState.isRecording,
            logPath = recState.currentLogDir,
        )
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

    data class State(
        val isRecording: Boolean,
        val logPath: File?,
    )

}
