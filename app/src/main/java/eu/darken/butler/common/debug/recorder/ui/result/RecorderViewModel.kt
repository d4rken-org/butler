package eu.darken.butler.common.debug.recorder.ui.result

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.DebugLogZipper
import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import eu.darken.butler.common.files.core.local.deleteAll
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RecorderViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val webpageTool: WebpageTool,
    private val debugLogZipper: DebugLogZipper,
    private val sessionManager: DebugSessionManager,
) : ViewModel4(dispatchers, logTag("Debug", "Recorder", "Screen", "VM")) {

    private val sessionPath = handle.get<String>(RecorderActivity.RECORD_PATH)?.let { File(it) }
    private val zipPath = sessionPath?.let { File(it.parentFile, "${it.name}.zip") }

    private val stater = DynamicStateFlow(TAG, vmScope) {
        State(logDir = sessionPath)
    }

    val state: Flow<State> = stater.flow

    val shareEvent = SingleEventFlow<Intent>()
    val closeEvent = SingleEventFlow<Unit>()

    init {
        launch {
            if (sessionPath == null) throw IllegalStateException("No recorded path found")

            log(TAG) { "Getting log files in dir: $sessionPath" }
            val logFiles = sessionPath.listFiles() ?: throw IllegalStateException("No log files found")

            log(TAG) { "Found ${logFiles.size} logfiles: $logFiles" }
            var entries = logFiles.map { LogFileItem(path = it) }
            stater.updateBlocking { copy(logEntries = entries) }

            log(TAG) { "Determining log file size..." }
            entries = entries.map { entry -> entry.copy(size = entry.path.length()) }.sortedByDescending { it.size }
            stater.updateBlocking { copy(logEntries = entries) }

            log(TAG) { "Compressing log files..." }
            val uri = debugLogZipper.zipAndGetUri(sessionPath)
            if (uri != null) {
                val zippedSize = zipPath?.length() ?: 0L
                log(TAG) { "Zip created ${zippedSize}B, uri=$uri" }
                sessionManager.refreshSessions()
                stater.updateBlocking { copy(compressedFile = zipPath, compressedSize = zippedSize, isWorking = false) }
            } else {
                log(TAG) { "No log files to compress in $sessionPath" }
                stater.updateBlocking { copy(isWorking = false) }
            }
        }
    }

    fun share() = launch {
        val file = stater.value().compressedFile ?: throw IllegalStateException("compressedFile is null")
        val uri = debugLogZipper.getUriForZip(file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            type = "application/zip"

            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "${BuildConfigWrap.APPLICATION_ID} DebugLog - ${BuildConfigWrap.VERSION_DESCRIPTION})"
            )
            putExtra(Intent.EXTRA_TEXT, "Your text here.")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooserIntent = Intent.createChooser(intent, context.getString(R.string.debug_log_file_label))
        shareEvent.emit(chooserIntent)
    }

    fun goPrivacyPolicy() {
        webpageTool.open(ButlerLinks.PRIVACY_POLICY)
    }

    fun discard() = launch {
        stater.updateBlocking { copy(isWorking = true) }
        zipPath?.let { if (it.exists()) it.delete() }
        sessionPath?.deleteAll()
        sessionManager.refreshSessions()
        closeEvent.emit(Unit)
    }

    fun save() = launch {
        stater.updateBlocking { copy(isWorking = true) }
        sessionPath?.deleteAll()
        sessionManager.refreshSessions()
        closeEvent.emit(Unit)
    }

    data class State(
        val logDir: File?,
        val logEntries: List<LogFileItem> = emptyList(),
        val compressedFile: File? = null,
        val compressedSize: Long? = null,
        val isWorking: Boolean = true,
    ) {

        fun getFormattedCompressedSize(context: Context): String? {
            return compressedSize?.let { Formatter.formatShortFileSize(context, it) }
        }
    }

    data class LogFileItem(
        val path: File,
        val size: Long? = null,
    ) {
        fun getFormattedSize(context: Context): String? {
            return size?.let { Formatter.formatShortFileSize(context, it) }
        }
    }

    companion object {
        internal val TAG = logTag("Debug", "Recorder", "ViewModel")
    }
}
