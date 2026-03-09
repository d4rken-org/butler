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
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.EmailTool
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.DebugSession
import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import eu.darken.butler.common.debug.recorder.core.RecorderManager
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import javax.inject.Inject

@HiltViewModel
class SupportContactFormViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val sessionManager: DebugSessionManager,
    private val emailTool: EmailTool,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Support", "ContactForm", "ViewModel")) {

    sealed interface Event {
        data class OpenEmail(val intent: Intent) : Event
        data class ShowSnackbar(val message: String) : Event
        data object ShowConsentDialog : Event
        data object ShowShortRecordingWarning : Event
    }

    val events = SingleEventFlow<Event>()

    private val stater = DynamicStateFlow(tag, vmScope) { State() }
    val state = stater.flow

    private val autoSelectSessionId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    init {
        combine(
            sessionManager.recorderState,
            sessionManager.sessions,
        ) { recorderState, allSessions ->
            val completed = allSessions.filterIsInstance<DebugSession.Ready>().take(MAX_PICKER_SESSIONS)
            stater.updateBlocking {
                val pendingAutoSelect = autoSelectSessionId.getAndSet(null)
                val newSelectedId = when {
                    pendingAutoSelect != null && completed.any { it.id == pendingAutoSelect } -> pendingAutoSelect
                    selectedSessionId != null
                        && completed.none { it.id == selectedSessionId }
                        && allSessions.none { it.id == selectedSessionId && it is DebugSession.Compressing }
                        -> null
                    else -> selectedSessionId
                }
                copy(
                    isRecording = recorderState.isRecording,
                    recordingStartedAt = recorderState.recordingStartedAt,
                    sessions = completed,
                    selectedSessionId = newSelectedId,
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

    fun selectLogSession(id: String) = launch {
        stater.updateBlocking { copy(selectedSessionId = id) }
    }

    fun deleteLogSession(id: String) = launch {
        log(tag) { "deleteLogSession($id)" }
        sessionManager.deleteSession(id)
    }

    fun refreshSessions() = launch {
        sessionManager.refresh()
    }

    fun startRecording() {
        events.tryEmit(Event.ShowConsentDialog)
    }

    fun doStartRecording() = launch {
        log(tag) { "doStartRecording()" }
        sessionManager.startRecording()
    }

    fun stopRecording() = launch {
        when (val result = sessionManager.requestStopRecording()) {
            is RecorderManager.StopResult.TooShort -> events.tryEmit(Event.ShowShortRecordingWarning)
            is RecorderManager.StopResult.Stopped -> {
                log(tag) { "stopRecording() -> ${result.sessionId}" }
                autoSelectSessionId.set(result.sessionId)
            }
            is RecorderManager.StopResult.NotRecording -> {}
        }
    }

    fun forceStopRecording() = launch {
        log(tag) { "forceStopRecording()" }
        val result = sessionManager.forceStopRecording()
        if (result != null) autoSelectSessionId.set(result.sessionId)
    }

    fun openPrivacyPolicy() {
        webpageTool.open(ButlerLinks.PRIVACY_POLICY)
    }

    fun confirmSent() = launch {
        val selectedId = stater.value().selectedSessionId
        if (selectedId != null) {
            log(tag) { "confirmSent() deleting session $selectedId" }
            sessionManager.deleteSession(selectedId)
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
            val attachment = buildAttachment(currentState.selectedSessionId)

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
    }

    private suspend fun buildAttachment(sessionId: String?): Uri? {
        if (sessionId == null) return null
        return try {
            sessionManager.getZipUri(sessionId)
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
        val sessions: List<DebugSession.Ready> = emptyList(),
        val selectedSessionId: String? = null,
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
        private const val MAX_PICKER_SESSIONS = 3
    }
}
