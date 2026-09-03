package eu.darken.butler.main.ui.settings.support.contactform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.BuildWrap
import eu.darken.butler.common.EmailTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@HiltViewModel
class SupportContactFormViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val bugReportRecorder: BugReportRecorder,
    private val bugReportRepo: BugReportRepo,
    private val emailTool: EmailTool,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Support", "ContactForm", "ViewModel")) {

    sealed interface Event {
        data class OpenEmail(val intent: Intent) : Event
        data class ShowSnackbar(val message: String) : Event
        data object ShowShortRecordingWarning : Event
    }

    val events = SingleEventFlow<Event>()

    private val stater = DynamicStateFlow(tag, vmScope) { State() }
    val state = stater.flow

    private val autoSelectReportId = AtomicReference<String?>(null)

    init {
        combine(
            bugReportRecorder.state,
            bugReportRepo.reports,
        ) { recState, allReports ->
            val completed = allReports.filter { !it.isOngoingRecording }.take(MAX_PICKER_REPORTS)
            stater.updateBlocking {
                // Apply the armed auto-selection only once the just-stopped report actually appears;
                // do NOT consume it on intermediate emissions where it isn't present yet.
                val desired = autoSelectReportId.get()
                val newSelectedId = when {
                    desired != null && completed.any { it.id == desired } -> {
                        autoSelectReportId.set(null)
                        desired
                    }

                    desired != null -> selectedReportId
                    selectedReportId != null && completed.none { it.id == selectedReportId } -> null
                    else -> selectedReportId
                }
                copy(
                    isRecording = recState.isRecording,
                    recordingStartedAt = recState.startedAtMs,
                    reports = completed,
                    selectedReportId = newSelectedId,
                )
            }
        }.launchIn(vmScope)
    }

    fun updateCategory(category: Category) = launch {
        stater.updateBlocking { copy(category = category) }
    }

    fun updateWorkspaceType(type: WorkspaceType) = launch {
        stater.updateBlocking { copy(workspaceType = type) }
    }

    fun updateDescription(text: String) = launch {
        if (text.length <= MAX_CHARS) {
            stater.updateBlocking { copy(description = text) }
        }
    }

    fun updateExpectedBehavior(text: String) = launch {
        if (text.length <= MAX_CHARS) {
            stater.updateBlocking { copy(expectedBehavior = text) }
        }
    }

    fun selectReport(id: String) = launch {
        stater.updateBlocking { copy(selectedReportId = id) }
    }

    fun deleteReport(id: String) = launch {
        log(tag) { "deleteReport($id)" }
        bugReportRepo.delete(id)
    }

    fun startRecording() = launch {
        log(tag) { "startRecording()" }
        bugReportRecorder.start()
    }

    fun stopRecording() = launch {
        // Arm before stopping: requestStop() clears recorder state (and thus emits) before returning,
        // so the auto-select must already be set when that emission reaches the combine.
        autoSelectReportId.set(bugReportRecorder.state.value.recordingId)
        when (val result = bugReportRecorder.requestStop()) {
            is BugReportRecorder.StopResult.TooShort -> {
                autoSelectReportId.set(null)
                events.tryEmit(Event.ShowShortRecordingWarning)
            }

            is BugReportRecorder.StopResult.Stopped -> log(tag) { "stopRecording() -> ${result.reportId}" }
            is BugReportRecorder.StopResult.NotRecording -> autoSelectReportId.set(null)
        }
    }

    fun forceStopRecording() = launch {
        log(tag) { "forceStopRecording()" }
        autoSelectReportId.set(bugReportRecorder.state.value.recordingId)
        bugReportRecorder.forceStop()
    }

    fun confirmSent() = launch {
        val selectedId = stater.value().selectedReportId
        if (selectedId != null) {
            log(tag) { "confirmSent() deleting report $selectedId" }
            bugReportRepo.delete(selectedId)
        }
        navUp()
    }

    fun send() = launch {
        val currentState = stater.value()
        if (!currentState.canSend) return@launch

        stater.updateBlocking { copy(isSending = true) }

        try {
            val subject = buildSubject(currentState)
            val body = buildBody(currentState)
            val attachment = buildAttachment(currentState.selectedReportId)

            val email = EmailTool.Email(
                recipients = listOf(SUPPORT_EMAIL),
                subject = subject,
                body = body,
                attachment = attachment,
            )

            val intent = emailTool.build(email, offerChooser = true)
            events.tryEmit(Event.OpenEmail(intent))
        } finally {
            stater.updateBlocking { copy(isSending = false) }
        }
    }

    private fun buildSubject(state: State): String {
        val categoryTag = state.category.tag
        val wsTag = state.workspaceType.tag
        val preview = state.description.trim().split("\\s+".toRegex()).take(8).joinToString(" ")
        return "[BUTLER][$categoryTag][$wsTag] $preview"
    }

    // Section headers are intentionally non-localizable — developer reads English
    private fun buildBody(state: State): String = buildString {
        appendLine(state.description.trim())

        if (state.isBug && state.expectedBehavior.isNotBlank()) {
            appendLine()
            appendLine("--- Expected behavior ---")
            appendLine(state.expectedBehavior.trim())
        }

        appendLine()
        appendLine("--- Device info ---")
        appendLine("App: ${BuildConfigWrap.VERSION_DESCRIPTION}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${BuildWrap.VERSION.SDK_INT})")
        appendLine("Fingerprint: ${BuildWrap.FINGERPRINT}")
        // What the user called the attached report, so a mail and a zip can be matched up by name.
        state.reports.firstOrNull { it.id == state.selectedReportId }?.report?.label?.let {
            appendLine("Report: $it")
        }
    }

    private suspend fun buildAttachment(reportId: String?): Uri? {
        if (reportId == null) return null
        return try {
            bugReportRepo.buildShareUri(reportId)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to prepare attachment: ${e.asLog()}" }
            events.tryEmit(
                Event.ShowSnackbar(context.getString(R.string.support_contact_debuglog_zip_error))
            )
            null
        }
    }

    enum class Category(val tag: String) {
        QUESTION("QUESTION"),
        FEATURE("FEATURE"),
        BUG("BUG"),
    }

    enum class WorkspaceType(val tag: String) {
        GENERAL("GENERAL"),
        EXPLORER("EXPLORER"),
        SEARCHER("SEARCHER"),
        EDITOR("EDITOR"),
    }

    data class State(
        val category: Category = Category.QUESTION,
        val workspaceType: WorkspaceType = WorkspaceType.GENERAL,
        val description: String = "",
        val expectedBehavior: String = "",
        val isSending: Boolean = false,
        val isRecording: Boolean = false,
        val recordingStartedAt: Long = 0L,
        val reports: List<BugReportInfo> = emptyList(),
        val selectedReportId: String? = null,
    ) {
        val isBug: Boolean get() = category == Category.BUG

        val descriptionWords: Int
            get() = description.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size

        val expectedWords: Int
            get() = expectedBehavior.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size

        val canSend: Boolean
            get() = descriptionWords >= MIN_DESCRIPTION_WORDS
                    && (!isBug || expectedWords >= MIN_EXPECTED_WORDS)
                    && !isSending
                    && !isRecording
    }

    companion object {
        private const val SUPPORT_EMAIL = "support@darken.eu"
        const val MAX_CHARS = 5000
        const val MIN_DESCRIPTION_WORDS = 20
        const val MIN_EXPECTED_WORDS = 10
        private const val MAX_PICKER_REPORTS = 3
    }
}
