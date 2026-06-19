package eu.darken.butler.bugreport.ui

import android.content.Intent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.combine

@HiltViewModel(assistedFactory = BugReportWorkspaceViewModel.Factory::class)
class BugReportWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val bugReportRepo: BugReportRepo,
    private val bugReportRecorder: BugReportRecorder,
) : ViewModel3(dispatchers, logTag("BugReport", "Workspace", id.shortTag, "Page")) {

    sealed interface Event {
        data object ShowShortRecordingWarning : Event
    }

    val events = SingleEventFlow<Event>()

    val state = combine(
        bugReportRepo.reports,
        bugReportRecorder.state,
    ) { reports, recorder ->
        State(
            id = id,
            reports = reports,
            isRecording = recorder.isRecording,
            recordingStartedAt = recorder.startedAtMs,
            recordingLogSize = recorder.currentLogSize,
        )
    }.asStateFlow()

    /** Acknowledge a report so a crash no longer auto-surfaces. Called on explicit user actions. */
    fun markSeen(reportId: String) = launch {
        bugReportRepo.markSeen(reportId)
    }

    fun delete(reportId: String) = launch {
        log(tag, INFO) { "delete($reportId)" }
        bugReportRepo.delete(reportId)
    }

    fun deleteAll() = launch {
        log(tag, INFO) { "deleteAll()" }
        bugReportRepo.deleteAll()
    }

    fun startRecording() = launch {
        log(tag, INFO) { "startRecording()" }
        bugReportRecorder.start()
    }

    fun stopRecording() = launch {
        log(tag, INFO) { "stopRecording()" }
        when (bugReportRecorder.requestStop()) {
            is BugReportRecorder.StopResult.TooShort -> events.tryEmit(Event.ShowShortRecordingWarning)
            is BugReportRecorder.StopResult.Stopped -> {}
            is BugReportRecorder.StopResult.NotRecording -> {}
        }
    }

    fun forceStopRecording() = launch {
        log(tag, INFO) { "forceStopRecording()" }
        bugReportRecorder.forceStop()
    }

    suspend fun loadLog(reportId: String): String = bugReportRepo.readLog(reportId)

    /** Build the share intent after the user has consented. The host wraps it in a chooser. */
    suspend fun buildShareIntent(reportId: String): Intent = bugReportRepo.buildShareIntent(reportId)
        .also { markSeen(reportId) }

    data class State(
        val id: Workspace.Id,
        val reports: List<BugReportInfo>,
        val isRecording: Boolean = false,
        val recordingStartedAt: Long = 0L,
        val recordingLogSize: Long = 0L,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): BugReportWorkspaceViewModel
    }
}
