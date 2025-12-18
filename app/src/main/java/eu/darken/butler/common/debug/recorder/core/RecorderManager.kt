package eu.darken.butler.common.debug.recorder.core

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.BuildWrap
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.ui.result.RecorderActivity
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.getPackageInfo
import eu.darken.butler.common.startServiceCompat
import eu.darken.butler.main.core.CurriculumVitae
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@Singleton
class RecorderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val butlerId: ButlerId,
    private val debugSettings: DebugSettings,
    private val curriculumVitae: CurriculumVitae,
) {

    private val triggerFile by lazy {
        try {
            File(context.getExternalFilesDir(null), FORCE_FILE)
        } catch (e: Exception) {
            File(
                Environment.getExternalStorageDirectory(),
                "/Android/data/${BuildConfigWrap.APPLICATION_ID}/files/$FORCE_FILE"
            )
        }
    }

    private val internalState = DynamicStateFlow(TAG, appScope + dispatcherProvider.IO) {
        val triggerFileExists = triggerFile.exists()
        State(shouldRecord = triggerFileExists || debugSettings.recorderPath.value() != null)
    }
    val state: Flow<State> = internalState.flow

    init {
        internalState.flow
            .onEach {
                log(TAG) { "New Recorder state: $internalState" }

                internalState.updateBlocking {
                    if (!isRecording && shouldRecord) {
                        val existingPath = debugSettings.recorderPath.value()
                        val logDir = existingPath?.let {
                            log(TAG) { "Continuing existing log: $it" }
                            File(it)
                        } ?: createRecordingDir().also {
                            log(TAG) { "Starting new log: $it" }
                            debugSettings.recorderPath.value(it.path)
                        }

                        val newRecorder = Recorder().apply { start(logDir) }

                        if (!triggerFile.exists()) triggerFile.createNewFile()

                        logInfos()

                        context.startServiceCompat(Intent(context, RecorderService::class.java))

                        // Start periodic log size updates
                        startLogSizeUpdates(logDir)

                        // Calculate initial size and estimate start time for continued recordings
                        val initialSize = if (existingPath != null) {
                            logDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        } else {
                            0L
                        }
                        val startTime = if (existingPath != null) {
                            // Use directory creation time as the recording start time
                            try {
                                val attrs = java.nio.file.Files.readAttributes(
                                    logDir.toPath(),
                                    java.nio.file.attribute.BasicFileAttributes::class.java
                                )
                                Instant.fromEpochMilliseconds(attrs.creationTime().toMillis())
                            } catch (e: Exception) {
                                log(TAG, WARN) { "Failed to get dir creation time: ${e.asLog()}" }
                                // Fallback to lastModified (stable unless new files are added)
                                logDir.lastModified().takeIf { it > 0 }
                                    ?.let { Instant.fromEpochMilliseconds(it) }
                                    ?: Clock.System.now()
                            }
                        } else {
                            Clock.System.now()
                        }

                        copy(
                            recorder = newRecorder,
                            currentLogDir = logDir,
                            recordingStartTime = startTime,
                            currentLogSize = initialSize,
                        )
                    } else if (!shouldRecord && isRecording) {
                        log(TAG) { "Stopping log recorder for: $currentLogDir" }
                        recorder!!.stop()

                        debugSettings.recorderPath.value(null)
                        if (triggerFile.exists() && !triggerFile.delete()) {
                            log(TAG, ERROR) { "Failed to delete trigger file" }
                        }

                        val intent = RecorderActivity.getLaunchIntent(context, currentLogDir!!.path).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)

                        copy(
                            recorder = null,
                            lastLogDir = currentLogDir,
                            recordingStartTime = null,
                            currentLogSize = 0L,
                        )
                    } else {
                        this
                    }
                }
            }
            .catch { log(TAG, ERROR) { "Log recording failed: ${it.asLog()}" } }
            .launchIn(appScope)
    }

    private fun createRecordingDir(): File {
        val pkg = BuildConfigWrap.APPLICATION_ID
        val version = BuildConfigWrap.VERSION_CODE
        val timestamp = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
            .withZone(ZoneId.systemDefault())
            .format(Clock.System.now().toJavaInstant())
        @Suppress("SetWorldWritable", "SetWorldReadable")
        return File(File(context.externalCacheDir, "debug/logs"), "${pkg}_${version}_${timestamp}").apply {
            mkdirs()
            if (setReadable(true, false)) log(TAG) { "Session dir is readable" }
            if (setWritable(true, false)) log(TAG) { "Session dir is writeable" }
        }
    }

    suspend fun startRecorder(): File {
        internalState.updateBlocking {
            copy(shouldRecord = true)
        }
        return internalState.flow.filter { it.isRecording }.first().currentLogDir!!
    }

    suspend fun stopRecorder(): File? {
        val currentPath = internalState.value().currentLogDir ?: return null
        internalState.updateBlocking {
            copy(shouldRecord = false)
        }
        internalState.flow.filter { !it.isRecording }.first()
        return currentPath
    }

    private fun startLogSizeUpdates(logDir: File) {
        appScope.launch(dispatcherProvider.IO) {
            while (isActive) {
                delay(LOG_SIZE_UPDATE_INTERVAL_MS)
                val currentState = internalState.value()
                if (!currentState.isRecording) break

                val size = try {
                    logDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to calculate log size: ${e.asLog()}" }
                    0L
                }

                internalState.updateBlocking {
                    if (isRecording) copy(currentLogSize = size) else this
                }
            }
        }
    }

    private suspend fun logInfos() {
        val pkgInfo = context.getPackageInfo()
        log(TAG, INFO) { "APILEVEL: ${BuildWrap.VERSION.SDK_INT}" }
        log(TAG, INFO) { "Build.FINGERPRINT: ${BuildWrap.FINGERPRINT}" }
        log(TAG, INFO) { "Build.MANUFACTOR: ${Build.MANUFACTURER}" }
        log(TAG, INFO) { "Build.BRAND: ${Build.BRAND}" }
        log(TAG, INFO) { "Build.PRODUCT: ${Build.PRODUCT}" }
        val versionInfo = "${pkgInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pkgInfo)})"
        log(TAG, INFO) { "App: ${context.packageName} - $versionInfo " }
        log(TAG, INFO) { "Build: ${BuildConfigWrap.FLAVOR}-${BuildConfigWrap.BUILD_TYPE}" }

        val installID = butlerId.id
        log(TAG, INFO) { "Install ID: $installID" }

        val locales = Resources.getSystem().configuration.locales
        log(TAG, INFO) { "App locales: $locales" }

        log(TAG, INFO) { "Update history: ${curriculumVitae.history.firstOrNull()}" }
    }

    data class State(
        val shouldRecord: Boolean = false,
        internal val recorder: Recorder? = null,
        val currentLogDir: File? = null,
        val lastLogDir: File? = null,
        val recordingStartTime: Instant? = null,
        val currentLogSize: Long = 0L,
    ) {
        val isRecording: Boolean
            get() = recorder != null
    }

    companion object {
        internal val TAG = logTag("Debug", "Log", "Recorder", "Manager")
        private const val FORCE_FILE = "force_debug_run"
        private const val LOG_SIZE_UPDATE_INTERVAL_MS = 5000L
    }
}