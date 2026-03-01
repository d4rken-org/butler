package eu.darken.butler.main.ui.settings.support.contactform

import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.BuildWrap
import eu.darken.butler.common.EmailTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.DebugLogZipper
import eu.darken.butler.common.debug.recorder.core.RecorderManager
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SupportContactFormViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val recorderManager: RecorderManager,
    private val emailTool: EmailTool,
    private val debugLogZipper: DebugLogZipper,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Support", "ContactForm", "ViewModel")) {

    private val formState = MutableStateFlow(FormState())
    private val logPickerState = MutableStateFlow(LogPickerState())

    val emailEvent = SingleEventFlow<Intent>()

    val state: Flow<State> = combine(
        formState,
        logPickerState,
        recorderManager.state,
    ) { form, picker, recState ->
        State(
            form = form,
            logPicker = picker.copy(isRecording = recState.isRecording),
            canSend = canSend(form, recState.isRecording),
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

    fun selectLogSession(file: File?) {
        logPickerState.value = logPickerState.value.copy(selectedZip = file)
    }

    fun toggleRecording() = launch {
        val isRecording = recorderManager.state.first().isRecording
        if (isRecording) {
            log(tag) { "Stopping recorder from contact form" }
            recorderManager.stopRecorder(showResult = false)
            scanLogSessions()
        } else {
            log(tag) { "Starting recorder from contact form" }
            recorderManager.startRecorder()
        }
    }

    fun deleteLogSession(file: File) = launch {
        log(tag) { "Deleting log session: $file" }
        try {
            file.delete()
            val dir = File(file.parentFile, file.nameWithoutExtension)
            if (dir.exists()) dir.deleteRecursively()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to delete log session: ${e.asLog()}" }
        }
        if (logPickerState.value.selectedZip == file) {
            logPickerState.value = logPickerState.value.copy(selectedZip = null)
        }
        scanLogSessions()
    }

    fun scanLogSessions() = launch {
        log(tag) { "Scanning log sessions" }
        val dirs = recorderManager.getLogDirectories()
        val zips = dirs.flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && it.extension == "zip" }?.toList() ?: emptyList()
        }.sortedByDescending { it.lastModified() }

        val current = logPickerState.value
        val selectedStillExists = current.selectedZip?.let { sel -> zips.any { it == sel } } ?: false
        logPickerState.value = current.copy(
            sessions = zips,
            selectedZip = if (selectedStillExists) current.selectedZip else null,
        )
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
        val selectedZip = logPickerState.value.selectedZip ?: return null
        if (!selectedZip.exists()) return null
        return try {
            debugLogZipper.getUriForZip(selectedZip)
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
        val sessions: List<File> = emptyList(),
        val selectedZip: File? = null,
    )

    data class State(
        val form: FormState = FormState(),
        val logPicker: LogPickerState = LogPickerState(),
        val canSend: Boolean = false,
    )

    companion object {
        private const val SUPPORT_EMAIL = "support@darken.eu"
        private const val MAX_CHARS = 5000
        private const val MIN_DESCRIPTION_WORDS = 20
        private const val MIN_EXPECTED_WORDS = 10

        fun wordCount(text: String): Int = text.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
    }
}
