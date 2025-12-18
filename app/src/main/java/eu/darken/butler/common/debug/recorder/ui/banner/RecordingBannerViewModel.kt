package eu.darken.butler.common.debug.recorder.ui.banner

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.RecorderModule
import eu.darken.butler.common.ui.ViewModel3
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import kotlin.time.Instant

@HiltViewModel
class RecordingBannerViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val recorderModule: RecorderModule,
) : ViewModel3(dispatcherProvider, TAG) {

    val state = recorderModule.state.map { recState ->
        State(
            isRecording = recState.isRecording,
            recordingStartTime = recState.recordingStartTime,
            currentLogSize = recState.currentLogSize,
        )
    }

    fun stopRecording() = launch {
        log(TAG) { "Stopping debug log recording from banner" }
        recorderModule.stopRecorder()
    }

    data class State(
        val isRecording: Boolean,
        val recordingStartTime: Instant?,
        val currentLogSize: Long,
    )

    companion object {
        private val TAG = logTag("Debug", "Recording", "Banner", "ViewModel")
    }
}
