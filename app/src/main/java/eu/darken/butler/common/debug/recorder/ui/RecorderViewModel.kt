package eu.darken.butler.common.debug.recorder.ui

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.compression.Zipper
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RecorderViewModel @Inject constructor(
    navCtrl: NavigationController,
    dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val webpageTool: WebpageTool,
    private val savedStateHandle: SavedStateHandle
) : ViewModel4(dispatchers, logTag("Debug", "Recorder","Screen","VM"), navCtrl) {

    private val recordedPath: File

    private val stater: DynamicStateFlow<State>
    val state: Flow<State>

    val shareEvent = SingleEventFlow<Intent>()

    init {
        val path = savedStateHandle.get<String>(RecorderActivity.RECORD_PATH)
            ?: throw IllegalStateException("No path provided")
        recordedPath = File(path)

        stater = DynamicStateFlow(TAG, vmScope) {
            State(logDir = recordedPath)
        }
        state = stater.flow

        launch {
            log(TAG) { "Getting log files in dir: $recordedPath" }
            val logFiles = recordedPath.listFiles() ?: emptyArray()
            log(TAG) { "Found ${logFiles.size} logfiles: $logFiles" }
            var entries = logFiles.map { LogFileItem(path = it) }
            stater.updateBlocking { copy(logEntries = entries) }

            log(TAG) { "Determining log file size..." }
            entries = entries.map { entry -> entry.copy(size = entry.path.length()) }.sortedByDescending { it.size }
            stater.updateBlocking { copy(logEntries = entries) }

            log(TAG) { "Compressing log files..." }
            val zipFile = File(recordedPath.parentFile, "${recordedPath.name}.zip")
            log(TAG) { "Writing zip file to $zipFile" }
            Zipper().zip(
                entries.map { it.path.path },
                zipFile.path
            )
            val zippedSize = zipFile.length()
            log(TAG) { "Zip file created ${zippedSize}B at $zipFile" }
            stater.updateBlocking { copy(compressedFile = zipFile, compressedSize = zippedSize) }
        }
    }

    fun share() = launch {
        val file = stater.value().compressedFile ?: throw IllegalStateException("compressedFile is null")

        val intent = Intent(Intent.ACTION_SEND).apply {
            val uri = FileProvider.getUriForFile(
                context,
                BuildConfigWrap.APPLICATION_ID + ".provider",
                file
            )

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

    data class State(
        val logDir: File,
        val logEntries: List<LogFileItem> = emptyList(),
        val compressedFile: File? = null,
        val compressedSize: Long? = null
    ) {
        val loading: Boolean
            get() = compressedSize == null

        fun getFormattedCompressedSize(context: Context): String? {
            return compressedSize?.let { Formatter.formatShortFileSize(context, it) }
        }
    }

    data class LogFileItem(
        val path: File,
        val size: Long? = null,
    ) {
        val stableId: Long = path.hashCode().toLong()

        fun getFormattedSize(context: Context): String? {
            return size?.let { Formatter.formatShortFileSize(context, it) }
        }
    }

    companion object {
        internal val TAG = logTag("Debug", "Recorder", "ViewModel")
    }
}
