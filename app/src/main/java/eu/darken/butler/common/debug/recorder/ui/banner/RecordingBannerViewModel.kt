package eu.darken.butler.common.debug.recorder.ui.banner

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import eu.darken.butler.common.debug.recorder.core.RecorderManager
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel3
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class RecordingBannerViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val sessionManager: DebugSessionManager,
) : ViewModel3(dispatcherProvider, TAG) {

    sealed interface Event {
        data object ShowShortRecordingWarning : Event
    }

    val events = SingleEventFlow<Event>()

    val state = sessionManager.recorderState.map { recState ->
        State(
            isRecording = recState.isRecording,
            recordingStartedAt = recState.recordingStartedAt,
            currentLogSize = recState.currentLogSize,
        )
    }

    fun stopRecording() = launch {
        log(TAG) { "Stopping debug log recording from banner" }
        when (sessionManager.requestStopRecording()) {
            is RecorderManager.StopResult.TooShort -> events.tryEmit(Event.ShowShortRecordingWarning)
            is RecorderManager.StopResult.Stopped -> {}
            is RecorderManager.StopResult.NotRecording -> {}
        }
    }

    fun forceStopRecording() = launch {
        log(TAG) { "Force stopping debug log recording from banner" }
        sessionManager.forceStopRecording()
    }

    data class State(
        val isRecording: Boolean,
        val recordingStartedAt: Long = 0L,
        val currentLogSize: Long = 0L,
    )

    companion object {
        private val TAG = logTag("Debug", "Recording", "Banner", "ViewModel")
    }
}
