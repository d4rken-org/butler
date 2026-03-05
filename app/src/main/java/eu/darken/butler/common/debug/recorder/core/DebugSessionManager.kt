package eu.darken.butler.common.debug.recorder.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugSessionManager @Inject constructor(
    private val recorderManager: RecorderManager,
    private val debugLogZipper: DebugLogZipper,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val _sessions = MutableStateFlow(Sessions())

    val state: Flow<State> = combine(
        recorderManager.state,
        _sessions,
    ) { recState, sessions ->
        State(
            activeSession = if (recState.isRecording) {
                DebugSession.Recording(
                    logDir = recState.currentLogDir!!,
                    startTime = recState.recordingStartTime!!,
                    currentSize = recState.currentLogSize,
                )
            } else null,
            completedSessions = sessions.completed,
            failedSessions = sessions.failed,
            shortRecordingWarning = if (recState.showShortRecordingWarning) {
                ShortRecordingWarning(origin = recState.shortRecordingWarningOrigin)
            } else null,
        )
    }

    init {
        appScope.launch(dispatcherProvider.IO) { refreshSessions() }
    }

    suspend fun startRecording(): File = recorderManager.startRecorder()

    suspend fun stopRecording(
        showResult: Boolean = true,
        force: Boolean = false,
        warningOrigin: String? = null,
    ): File? {
        val result = recorderManager.stopRecorder(showResult, force, warningOrigin)
        if (!showResult && result != null) refreshSessions()
        return result
    }

    suspend fun dismissShortRecordingWarning() = recorderManager.dismissShortRecordingWarning()

    suspend fun refreshSessions() {
        val activeDir = recorderManager.state.first().currentLogDir

        val completed = mutableListOf<DebugSession.Completed>()
        val failed = mutableListOf<DebugSession.Failed>()

        for (logRoot in recorderManager.getLogDirectories()) {
            val entries = logRoot.listFiles() ?: continue
            val zipNames = entries
                .filter { it.isFile && it.extension == "zip" }
                .map { it.nameWithoutExtension }
                .toSet()

            for (entry in entries) {
                if (entry == activeDir) continue

                if (entry.isFile && entry.extension == "zip") {
                    completed += DebugSession.Completed(
                        zipFile = entry,
                        zipSize = entry.length(),
                    )
                } else if (entry.isDirectory && entry.name !in zipNames) {
                    val dirSize = try {
                        entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to calculate dir size for $entry: ${e.asLog()}" }
                        0L
                    }
                    failed += DebugSession.Failed(
                        sessionDir = entry,
                        dirSize = dirSize,
                    )
                }
            }
        }

        completed.sortByDescending { it.zipFile.lastModified() }
        failed.sortByDescending { it.sessionDir.lastModified() }

        log(TAG) { "refreshSessions(): ${completed.size} completed, ${failed.size} failed" }
        _sessions.value = Sessions(completed = completed, failed = failed)
    }

    suspend fun deleteSession(session: DebugSession) {
        log(TAG) { "deleteSession(): $session" }
        try {
            when (session) {
                is DebugSession.Completed -> {
                    session.zipFile.delete()
                    val dir = File(session.zipFile.parentFile, session.zipFile.nameWithoutExtension)
                    if (dir.exists()) dir.deleteRecursively()
                }
                is DebugSession.Failed -> {
                    session.sessionDir.deleteRecursively()
                }
                is DebugSession.Recording -> {
                    log(TAG, WARN) { "Cannot delete active recording session" }
                    return
                }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to delete session: ${e.asLog()}" }
        }
        refreshSessions()
    }

    suspend fun deleteAllSessions() {
        log(TAG) { "deleteAllSessions()" }
        val activeDir = recorderManager.state.first().currentLogDir
        for (dir in recorderManager.getLogDirectories()) {
            dir.listFiles()?.forEach { entry ->
                if (entry == activeDir) {
                    log(TAG) { "Skipping active recording dir: $entry" }
                    return@forEach
                }
                try {
                    if (entry.isDirectory) {
                        entry.deleteRecursively()
                    } else {
                        entry.delete()
                    }
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to delete $entry: ${e.asLog()}" }
                }
            }
        }
        refreshSessions()
    }

    suspend fun zipSession(sessionDir: File): DebugSession.Completed? {
        log(TAG) { "zipSession(): $sessionDir" }
        debugLogZipper.zipAndGetUri(sessionDir) ?: return null
        val zipFile = File(sessionDir.parentFile, "${sessionDir.name}.zip")
        refreshSessions()
        return if (zipFile.exists()) {
            DebugSession.Completed(zipFile = zipFile, zipSize = zipFile.length())
        } else null
    }

    private data class Sessions(
        val completed: List<DebugSession.Completed> = emptyList(),
        val failed: List<DebugSession.Failed> = emptyList(),
    )

    data class State(
        val activeSession: DebugSession.Recording? = null,
        val completedSessions: List<DebugSession.Completed> = emptyList(),
        val failedSessions: List<DebugSession.Failed> = emptyList(),
        val shortRecordingWarning: ShortRecordingWarning? = null,
    )

    data class ShortRecordingWarning(val origin: String?)

    companion object {
        private val TAG = logTag("Debug", "Session", "Manager")
    }
}
