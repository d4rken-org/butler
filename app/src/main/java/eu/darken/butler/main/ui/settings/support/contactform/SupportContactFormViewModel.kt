package eu.darken.butler.main.ui.settings.support.contactform

import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.BuildWrap
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.EmailTool
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.DebugLogZipper
import eu.darken.butler.common.debug.recorder.core.DebugSession
import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class SupportContactFormViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val sessionManager: DebugSessionManager,
    private val emailTool: EmailTool,
    private val debugLogZipper: DebugLogZipper,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Support", "ContactForm", "ViewModel")) {

    private val formState = MutableStateFlow(FormState())
    private val selectedSession = MutableStateFlow<DebugSession.Completed?>(null)

    val emailEvent = SingleEventFlow<Intent>()

    val state: Flow<State> = combine(
        formState,
        selectedSession,
        sessionManager.state,
    ) { form, selected, sessState ->
        val validSelected = selected?.takeIf { sel -> sessState.completedSessions.any { it == sel } }
        State(
            form = form,
            logPicker = LogPickerState(
                isRecording = sessState.activeSession != null,
                sessions = sessState.completedSessions,
                selectedSession = validSelected,
            ),
            canSend = canSend(form, sessState.activeSession != null),
            showShortRecordingWarning = sessState.shortRecordingWarning?.origin == WARNING_ORIGIN,
        )
    }

    fun updateCategory(category: Category) {
        formState.value = formState.value.copy(category = category)
    }

    fun updateWorkspaceType(type: WorkspaceType) {
        formState.value = formState.value.copy(workspaceType = type)
    }

    fun updateDescription(text: String) {
        formState.value = formState.value.copy(description = text.take(MAX_CHARS))
    }

    fun updateExpectedBehavior(text: String) {
        formState.value = formState.value.copy(expectedBehavior = text.take(MAX_CHARS))
    }

    fun selectLogSession(session: DebugSession.Completed?) {
        selectedSession.value = session
    }

    fun toggleRecording() = launch {
        val isRecording = sessionManager.state.first().activeSession != null
        if (isRecording) {
            log(tag) { "Stopping recorder from contact form" }
            val sessionDir = sessionManager.stopRecording(showResult = false, warningOrigin = WARNING_ORIGIN)
            zipAndSelect(sessionDir)
        } else {
            log(tag) { "Starting recorder from contact form" }
            sessionManager.startRecording()
        }
    }

    fun dismissShortRecordingWarning() = launch {
        sessionManager.dismissShortRecordingWarning()
    }

    fun forceStopRecording() = launch {
        log(tag) { "Force stopping recorder from contact form" }
        val sessionDir = sessionManager.stopRecording(showResult = false, force = true)
        zipAndSelect(sessionDir)
    }

    private suspend fun zipAndSelect(sessionDir: java.io.File?) {
        if (sessionDir != null) {
            log(tag) { "Zipping session dir: $sessionDir" }
            val completed = sessionManager.zipSession(sessionDir)
            selectedSession.value = completed
        } else {
            sessionManager.refreshSessions()
        }
    }

    fun openPrivacyPolicy() {
        webpageTool.open(ButlerLinks.PRIVACY_POLICY)
    }

    fun deleteLogSession(session: DebugSession.Completed) = launch {
        log(tag) { "Deleting log session: $session" }
        if (selectedSession.value == session) {
            selectedSession.value = null
        }
        sessionManager.deleteSession(session)
    }

    fun refreshSessions() = launch {
        sessionManager.refreshSessions()
    }

    fun send() = launch {
        val form = formState.value
        if (form.isSending) return@launch

        formState.value = form.copy(isSending = true)
        try {
            val subject = buildSubject(form)
            val body = buildBody(form)
            val attachment = buildAttachment()

            val email = EmailTool.Email(
                recipients = listOf(SUPPORT_EMAIL),
                subject = subject,
                body = body,
                attachment = attachment,
            )

            val intent = emailTool.build(email, offerChooser = true)
            emailEvent.emit(intent)
        } finally {
            formState.value = formState.value.copy(isSending = false)
        }
    }

    private fun buildSubject(form: FormState): String {
        val categoryTag = form.category.tag
        val wsTag = form.workspaceType.tag
        val preview = form.description.split("\\s+".toRegex()).take(8).joinToString(" ")
        return "[BUTLER][$categoryTag][$wsTag] $preview"
    }

    private fun buildBody(form: FormState): String = buildString {
        appendLine(form.description)

        if (form.category == Category.BUG && form.expectedBehavior.isNotBlank()) {
            appendLine()
            appendLine("--- Expected behavior ---")
            appendLine(form.expectedBehavior)
        }

        appendLine()
        appendLine("--- Device info ---")
        appendLine("App: ${BuildConfigWrap.VERSION_DESCRIPTION}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${BuildWrap.VERSION.SDK_INT})")
        appendLine("Fingerprint: ${BuildWrap.FINGERPRINT}")
    }

    private fun buildAttachment(): Uri? {
        val zip = selectedSession.value?.zipFile ?: return null
        if (!zip.exists()) return null
        return try {
            debugLogZipper.getUriForZip(zip)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get URI for zip: ${e.asLog()}" }
            null
        }
    }

    private fun canSend(form: FormState, isRecording: Boolean): Boolean {
        if (form.isSending) return false
        if (isRecording) return false
        if (wordCount(form.description) < MIN_DESCRIPTION_WORDS) return false
        if (form.category == Category.BUG && wordCount(form.expectedBehavior) < MIN_EXPECTED_WORDS) return false
        return true
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

    data class FormState(
        val category: Category = Category.QUESTION,
        val workspaceType: WorkspaceType = WorkspaceType.GENERAL,
        val description: String = "",
        val expectedBehavior: String = "",
        val isSending: Boolean = false,
    )

    data class LogPickerState(
        val isRecording: Boolean = false,
        val sessions: List<DebugSession.Completed> = emptyList(),
        val selectedSession: DebugSession.Completed? = null,
    )

    data class State(
        val form: FormState = FormState(),
        val logPicker: LogPickerState = LogPickerState(),
        val canSend: Boolean = false,
        val showShortRecordingWarning: Boolean = false,
    )

    companion object {
        private const val WARNING_ORIGIN = "contact_form"
        private const val SUPPORT_EMAIL = "support@darken.eu"
        const val MAX_CHARS = 5000
        const val MIN_DESCRIPTION_WORDS = 20
        const val MIN_EXPECTED_WORDS = 10

        fun wordCount(text: String): Int = text.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
    }
}
