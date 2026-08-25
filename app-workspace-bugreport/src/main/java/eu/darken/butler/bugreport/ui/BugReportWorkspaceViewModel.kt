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
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

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

    /** The report currently shown in the full-screen detail view, or null while on the list. */
    private val selectedReportId = MutableStateFlow<String?>(null)

    // Overlay visibility lives here rather than in the page host: the page and its overlays are
    // siblings, so a `remember` in the page would be a different instance from the one the overlays
    // read — and the page's back handler has to see the same values.
    private val _overlayState = MutableStateFlow(OverlayState())
    val overlayState: StateFlow<OverlayState> = _overlayState

    // First-wins: a request arriving while another dialog is up is dropped, so a destructive
    // confirmation can never turn into a different question under the user's finger.
    private fun requestDialog(dialog: ActiveDialog) {
        log(tag) { "requestDialog($dialog)" }
        _overlayState.update { if (it.activeDialog != null) it else it.copy(activeDialog = dialog) }
    }

    fun requestShareConsent(reportId: String) = requestDialog(ActiveDialog.ShareConsent(reportId))

    fun dismissShareConsent() = _overlayState.update {
        if (it.activeDialog is ActiveDialog.ShareConsent) it.copy(activeDialog = null) else it
    }

    fun showShortRecordingWarning() = requestDialog(ActiveDialog.ShortRecordingWarning)

    fun dismissShortRecordingWarning() = _overlayState.update {
        if (it.activeDialog is ActiveDialog.ShortRecordingWarning) it.copy(activeDialog = null) else it
    }

    fun requestDeleteAllConfirmation() = requestDialog(ActiveDialog.DeleteAllConfirmation)

    fun dismissDeleteAllConfirmation() = _overlayState.update {
        if (it.activeDialog is ActiveDialog.DeleteAllConfirmation) it.copy(activeDialog = null) else it
    }

    // Loads the selected report's log tail. flatMapLatest cancels an in-flight load when the selection
    // changes, and runCatching keeps a read failure (e.g. the report deleted between scan and read)
    // from killing the collector — it surfaces as LogState.Error instead.
    private val detailLog: Flow<DetailLog?> = selectedReportId.flatMapLatest { reportId ->
        if (reportId == null) {
            flowOf(null)
        } else {
            flow {
                emit(DetailLog(reportId, LogState.Loading))
                val logState = try {
                    val tail = bugReportRepo.readLogTail(reportId, MAX_LOG_PREVIEW_LINES)
                    if (tail.totalLines == 0) {
                        LogState.Empty
                    } else {
                        LogState.Loaded(
                            lines = tail.lines,
                            totalLines = tail.totalLines,
                            shownLines = tail.lines.size,
                            isTruncated = tail.totalLines > tail.lines.size,
                        )
                    }
                } catch (e: CancellationException) {
                    // Selection changed/closed mid-read — let cancellation propagate, don't log an error.
                    throw e
                } catch (e: Exception) {
                    log(tag, ERROR) { "readLogTail failed for $reportId: ${e.asLog()}" }
                    LogState.Error
                }
                emit(DetailLog(reportId, logState))
            }
        }
    }

    val state = combine(
        bugReportRepo.reports,
        bugReportRecorder.state,
        selectedReportId,
        detailLog,
    ) { reports, recorder, selectedId, loadedLog ->
        // Derive the detail from the live list: if the selected report is gone (deleted/pruned), the
        // detail collapses to null and the UI returns to the list automatically.
        val detail = selectedId?.let { sid ->
            reports.firstOrNull { it.id == sid }?.let { info ->
                Detail(
                    info = info,
                    logState = loadedLog?.takeIf { it.reportId == sid }?.state ?: LogState.Loading,
                )
            }
        }
        State(
            id = id,
            reports = reports,
            isRecording = recorder.isRecording,
            recordingStartedAt = recorder.startedAtMs,
            recordingLogSize = recorder.currentLogSize,
            detail = detail,
        )
    }.asStateFlow()

    fun openReport(reportId: String) {
        log(tag, INFO) { "openReport($reportId)" }
        selectedReportId.value = reportId
        markSeen(reportId)
    }

    fun closeReport() {
        selectedReportId.value = null
    }

    /** Acknowledge a report so a crash no longer auto-surfaces. Called on explicit user actions. */
    fun markSeen(reportId: String) = launch {
        bugReportRepo.markSeen(reportId)
    }

    fun delete(reportId: String) = launch {
        log(tag, INFO) { "delete($reportId)" }
        bugReportRepo.delete(reportId)
        if (selectedReportId.value == reportId) selectedReportId.value = null
    }

    fun deleteAll() {
        log(tag, INFO) { "deleteAll()" }
        // Dismiss before the IO, like the share consent does: otherwise the confirm button stays live
        // during the delete, and a throw inside deleteAll() would strand the dialog open.
        dismissDeleteAllConfirmation()
        launch {
            bugReportRepo.deleteAll()
            selectedReportId.value = null
        }
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

    /** Build the share intent after the user has consented. The host wraps it in a chooser. */
    suspend fun buildShareIntent(reportId: String): Intent = bugReportRepo.buildShareIntent(reportId)
        .also { markSeen(reportId) }

    data class State(
        val id: Workspace.Id,
        val reports: List<BugReportInfo>,
        val isRecording: Boolean = false,
        val recordingStartedAt: Long = 0L,
        val recordingLogSize: Long = 0L,
        val detail: Detail? = null,
    )

    /** A single slot, so two dialogs can never stack on top of each other. */
    data class OverlayState(
        val activeDialog: ActiveDialog? = null,
    )

    sealed interface ActiveDialog {
        data class ShareConsent(val reportId: String) : ActiveDialog
        data object ShortRecordingWarning : ActiveDialog
        data object DeleteAllConfirmation : ActiveDialog
    }

    /** The full-screen detail view's state: the report plus its (async) log tail. */
    data class Detail(
        val info: BugReportInfo,
        val logState: LogState,
    )

    sealed interface LogState {
        data object Loading : LogState
        data class Loaded(
            val lines: List<String>,
            val totalLines: Int,
            val shownLines: Int,
            val isTruncated: Boolean,
        ) : LogState

        data object Empty : LogState
        data object Error : LogState
    }

    private data class DetailLog(
        val reportId: String,
        val state: LogState,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): BugReportWorkspaceViewModel
    }

    companion object {
        /** Tail size shown in the detail view; the full log always travels in the shared zip. */
        private const val MAX_LOG_PREVIEW_LINES = 300
    }
}
