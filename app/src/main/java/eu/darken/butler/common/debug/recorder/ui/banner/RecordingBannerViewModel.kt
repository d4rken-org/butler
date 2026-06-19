package eu.darken.butler.common.debug.recorder.ui.banner

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.contracts.bugreport.BugReportArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.ui.workspaces.workspaces
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class RecordingBannerViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val bugReportRecorder: BugReportRecorder,
    private val workspaceRemote: WorkspaceRemote,
) : ViewModel4(dispatcherProvider, TAG) {

    sealed interface Event {
        data object ShowShortRecordingWarning : Event
    }

    val events = SingleEventFlow<Event>()

    val state = bugReportRecorder.state.map { recState ->
        State(
            isRecording = recState.isRecording,
            recordingStartedAt = recState.startedAtMs,
            currentLogSize = recState.currentLogSize,
        )
    }

    fun stopRecording() = launch {
        log(TAG) { "Stopping recording from banner" }
        when (bugReportRecorder.requestStop()) {
            is BugReportRecorder.StopResult.TooShort -> events.tryEmit(Event.ShowShortRecordingWarning)
            is BugReportRecorder.StopResult.Stopped -> {}
            is BugReportRecorder.StopResult.NotRecording -> {}
        }
    }

    fun forceStopRecording() = launch {
        log(TAG) { "Force stopping recording from banner" }
        bugReportRecorder.forceStop()
    }

    /** Open/focus the Bug reports workspace without stopping the active recording. */
    fun openBugReports() = launch {
        log(TAG) { "openBugReports()" }
        workspaceRemote.createAndFocus(Workspace.Type.BUG_REPORT, BugReportArguments.Default())
        // Single-top: the banner is always visible, so repeated taps must not re-create the
        // workspaces nav entry. Navigation runs on the main thread via NavigationEventHandler.
        navToSingleTop(Nav.Main.workspaces())
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
