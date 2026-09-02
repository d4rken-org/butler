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
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

@HiltViewModel(assistedFactory = BugReportWorkspaceViewModel.Factory::class)
class BugReportWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val bugReportRepo: BugReportRepo,
    private val bugReportRecorder: BugReportRecorder,
) : ViewModel3(dispatchers, logTag("BugReport", "Workspace", id.shortTag, "Page")) {

    // One object rather than one flow per field: a report switch has to be observable as a single
    // value, otherwise a collector can see the new report paired with the previous report's
    // expansion and start a full log scan that is immediately cancelled again.
    private val logSelection = MutableStateFlow(LogSelection())

    // What the last completed read produced, so a retry can be told apart from a cached tail.
    private val lastLogState = MutableStateFlow<LogState?>(null)

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

    fun dismissShortRecordingWarning() = _overlayState.update {
        if (it.activeDialog is ActiveDialog.ShortRecordingWarning) it.copy(activeDialog = null) else it
    }

    fun requestDeleteAllConfirmation() = requestDialog(ActiveDialog.DeleteAllConfirmation)

    fun dismissDeleteAllConfirmation() = _overlayState.update {
        if (it.activeDialog is ActiveDialog.DeleteAllConfirmation) it.copy(activeDialog = null) else it
    }

    // Loads the selected report's log tail once the user has asked for it — a requestId of 0 means
    // the section was never expanded, so nothing is read from disk. Keying on report + request id
    // (distinct) is what keeps a plain collapse/expand from restarting a completed read.
    // flatMapLatest cancels an in-flight load when either changes, and catching a read failure
    // (e.g. the report deleted between scan and read) keeps it from killing the collector — it
    // surfaces as LogState.Error instead.
    private val detailLog: Flow<DetailLog?> = logSelection
        .map { it.reportId to it.requestId }
        .distinctUntilChanged()
        .flatMapLatest { (reportId, requestId) ->
            if (reportId == null || requestId == 0) {
                flowOf<DetailLog?>(null)
            } else {
                flow<DetailLog?> {
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
        .onEach { lastLogState.value = it?.state }

    val state = combine(
        bugReportRepo.reports,
        bugReportRecorder.state,
        logSelection,
        detailLog,
    ) { reports, recorder, selection, loadedLog ->
        // Derive the detail from the live list: if the selected report is gone (deleted/pruned), the
        // detail collapses to null and the UI returns to the list automatically.
        val detail = selection.reportId?.let { sid ->
            reports.firstOrNull { it.id == sid }?.let { info ->
                Detail(
                    info = info,
                    logState = loadedLog?.takeIf { it.reportId == sid }?.state
                        ?: if (selection.expanded) LogState.Loading else LogState.Idle,
                    isLogExpanded = selection.expanded,
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

    /** Every selection change goes through here, so a report switch always resets the log state too. */
    private fun selectReport(reportId: String?) {
        logSelection.value = LogSelection(reportId)
        lastLogState.value = null
    }

    fun openReport(reportId: String) {
        log(tag, INFO) { "openReport($reportId)" }
        selectReport(reportId)
        markSeen(reportId)
    }

    fun closeReport() {
        selectReport(null)
    }

    fun setLogExpanded(expanded: Boolean) {
        logSelection.update { current ->
            // A new request id restarts the read. Re-expanding after a failure is the retry path; a
            // successful tail stays cached across collapse/expand cycles.
            val needsLoad = expanded && (current.requestId == 0 || lastLogState.value == LogState.Error)
            current.copy(
                expanded = expanded,
                requestId = if (needsLoad) current.requestId + 1 else current.requestId,
            )
        }
    }

    /** Acknowledge a report so a crash no longer auto-surfaces. Called on explicit user actions. */
    fun markSeen(reportId: String) = launch {
        bugReportRepo.markSeen(reportId)
    }

    fun delete(reportId: String) = launch {
        log(tag, INFO) { "delete($reportId)" }
        bugReportRepo.delete(reportId)
        if (logSelection.value.reportId == reportId) selectReport(null)
    }

    fun deleteAll() {
        log(tag, INFO) { "deleteAll()" }
        // Dismiss before the IO, like the share consent does: otherwise the confirm button stays live
        // during the delete, and a throw inside deleteAll() would strand the dialog open.
        dismissDeleteAllConfirmation()
        launch {
            bugReportRepo.deleteAll()
            selectReport(null)
        }
    }

    fun startRecording() = launch {
        log(tag, INFO) { "startRecording()" }
        bugReportRecorder.start()
    }

    fun stopRecording() = launch {
        log(tag, INFO) { "stopRecording()" }
        when (bugReportRecorder.requestStop()) {
            is BugReportRecorder.StopResult.TooShort -> requestDialog(ActiveDialog.ShortRecordingWarning)
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
        val isLogExpanded: Boolean,
    )

    sealed interface LogState {
        /** The log has not been requested yet — the section is collapsed and nothing was read. */
        data object Idle : LogState
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

    /** [requestId] 0 means the log was never requested; each increment starts a fresh read. */
    private data class LogSelection(
        val reportId: String? = null,
        val expanded: Boolean = false,
        val requestId: Int = 0,
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
