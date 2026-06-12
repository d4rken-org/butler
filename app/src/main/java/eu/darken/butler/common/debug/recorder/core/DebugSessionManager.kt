package eu.darken.butler.common.debug.recorder.core

import android.net.Uri
import androidx.annotation.VisibleForTesting
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class DebugSessionManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val recorderManager: RecorderManager,
    private val debugLogZipper: DebugLogZipper,
) {

    private val fsMutex = Mutex()
    private val zippingIds = MutableStateFlow<Set<String>>(emptySet())
    private val failedZipIds = MutableStateFlow<Set<String>>(emptySet())
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val pendingAutoZips: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    val recorderState: Flow<RecorderManager.State> get() = recorderManager.state

    val sessions: Flow<List<DebugSession>> = combine(
        recorderManager.state,
        zippingIds,
        failedZipIds,
        refreshTrigger.onStart { emit(Unit) },
    ) { recorderState, zipping, failedZips, _ ->
        val raw = withContext(dispatcherProvider.IO) {
            scanSessions(
                logDirectories = recorderManager.getLogDirectories(),
                activeDir = recorderState.currentLogDir,
                recordingStartedAt = Instant.fromEpochMilliseconds(recorderState.recordingStartedAt),
            )
        }
        val overlaid = applyOverlays(raw, zipping, failedZips)

        // Orphan detection reads the LIVE zippingIds/failedZipIds instead of this emission's
        // captured values: a scan queued before a zip claimed its id would otherwise run with a
        // stale set and schedule a duplicate zip. The captured values are only used for the
        // display overlays, where a stale frame is corrected by the refresh() on zip completion.
        val orphans = findOrphans(raw, zippingIds.value, failedZipIds.value)
        orphans.forEach { (id, dir) ->
            if (pendingAutoZips.add(id)) {
                appScope.launch {
                    log(TAG, INFO) { "Orphan session detected, auto-zipping: $id" }
                    zipSessionAsync(id, dir)
                }
            }
        }

        overlaid
    }.replayingShare(appScope)

    private fun applyOverlays(
        sessions: List<DebugSession>,
        zipping: Set<String>,
        failedZips: Set<String>,
    ): List<DebugSession> = sessions.map { session ->
        when {
            session.id in zipping -> {
                val ready = session as? DebugSession.Ready
                val path = ready?.logDir ?: ready?.zipFile
                if (path == null) log(TAG, WARN) { "No logDir/zipFile for session in zippingIds: ${session.id}" }
                DebugSession.Compressing(
                    id = session.id,
                    displayName = session.displayName,
                    createdAt = session.createdAt,
                    diskSize = session.diskSize,
                    path = path ?: File(session.displayName),
                )
            }

            // A valid zip found on disk wins over a stale failure overlay: a successful retry
            // must not keep presenting the session as Failed.
            session.id in failedZips && session !is DebugSession.Failed &&
                (session as? DebugSession.Ready)?.zipFile == null -> {
                val ready = session as? DebugSession.Ready
                val path = ready?.logDir ?: ready?.zipFile
                if (path == null) log(TAG, WARN) { "No logDir/zipFile for failed-zip session: ${session.id}" }
                DebugSession.Failed(
                    id = session.id,
                    displayName = session.displayName,
                    createdAt = session.createdAt,
                    diskSize = session.diskSize,
                    path = path ?: File(session.displayName),
                    reason = DebugSession.Failed.Reason.ZIP_FAILED,
                )
            }

            else -> session
        }
    }

    private fun findOrphans(
        sessions: List<DebugSession>,
        zipping: Set<String>,
        failedZips: Set<String>,
    ): List<Pair<String, File>> {
        // failedZips sessions are excluded so a failing zip isn't endlessly re-attempted on
        // every scan. One auto-zip attempt per process; the user can retry via Share, which
        // goes through zipSession().
        return sessions.filterIsInstance<DebugSession.Ready>()
            .filter { it.logDir != null && it.id !in zipping && it.id !in failedZips }
            .filter { it.zipFile == null || it.compressedSize == 0L }
            .map { it.id to it.logDir!! }
    }

    /** Atomically claim the per-session zip slot; returns false if another zip owns it. */
    private fun tryClaimZip(sessionId: String): Boolean =
        sessionId !in zippingIds.getAndUpdate { it + sessionId }

    /** Claim the per-session zip slot, suspending until any in-flight zip releases it. */
    private suspend fun claimZip(sessionId: String) {
        while (!tryClaimZip(sessionId)) {
            log(TAG) { "Waiting for in-flight zip of $sessionId before claiming" }
            zippingIds.first { sessionId !in it }
        }
    }

    private fun zipSessionAsync(sessionId: String, logDir: File) {
        // A stale scan can request a zip for a session that's already being zipped (e.g. the
        // orphan auto-zip racing requestStopRecording's own zip). Only one job may own the
        // claim — a duplicate would otherwise release the shared zippingIds entry while the
        // other zip is still running, dropping the Compressing overlay early and disarming
        // deleteSession's in-flight guard.
        if (!tryClaimZip(sessionId)) {
            log(TAG) { "Skipping duplicate zip for $sessionId, already in flight" }
            pendingAutoZips.remove(sessionId)
            return
        }
        appScope.launch(dispatcherProvider.IO) {
            try {
                fsMutex.withLock {
                    // A scan that straddled a completed zip can request a redundant re-zip:
                    // its raw result predates the zip, but the live orphan check ran after the
                    // claim was released. Re-check the disk before doing the work again.
                    val existingZip = File(logDir.parentFile, "${logDir.name}.zip")
                    if (existingZip.length() > 0 && existingZip.lastModified() >= logDir.lastModified()) {
                        log(TAG) { "Valid zip already exists for $sessionId, skipping" }
                    } else {
                        debugLogZipper.zip(logDir)
                    }
                }
                failedZipIds.update { it - sessionId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, ERROR) { "Zipping failed for $sessionId: $e" }
                failedZipIds.update { it + sessionId }
            } finally {
                pendingAutoZips.remove(sessionId)
                zippingIds.update { it - sessionId }
                refresh()
            }
        }
    }

    suspend fun startRecording(): File = recorderManager.startRecorder()

    suspend fun requestStopRecording(): RecorderManager.StopResult {
        val result = recorderManager.requestStopRecorder()
        if (result is RecorderManager.StopResult.Stopped) {
            zipSessionAsync(result.sessionId, result.logDir)
        }
        return result
    }

    suspend fun forceStopRecording(): RecorderManager.StopResult.Stopped? {
        val logDir = recorderManager.stopRecorder() ?: return null
        val sessionId = deriveSessionId(logDir)
        zipSessionAsync(sessionId, logDir)
        return RecorderManager.StopResult.Stopped(logDir, sessionId)
    }

    fun refresh() {
        refreshTrigger.tryEmit(Unit)
    }

    private fun activeSessionId(): String? = recorderManager.currentLogDir?.let { deriveSessionId(it) }

    suspend fun zipSession(sessionId: String): File {
        // Claim BEFORE fsMutex (same order as zipSessionAsync, avoiding lock inversion). The
        // claim presents the session as Compressing and keeps deleteSession away from it.
        claimZip(sessionId)
        try {
            return fsMutex.withLock {
                // Do NOT call sessions.first() here — deadlock risk with fsMutex
                require(activeSessionId() != sessionId) { "Cannot zip an active recording session" }

                val (dir, existingZip) = findSessionFiles(sessionId)

                if (existingZip != null && existingZip.length() > 0) {
                    if (dir == null || existingZip.lastModified() >= dir.lastModified()) {
                        failedZipIds.update { it - sessionId }
                        return@withLock existingZip
                    }
                }

                requireNotNull(dir) { "No log directory found for session $sessionId" }
                // The failure marker only clears on proven success — clearing it upfront would
                // re-qualify the session as an auto-zip orphan if this retry fails too.
                val zip = try {
                    withContext(dispatcherProvider.IO) {
                        debugLogZipper.zip(dir)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedZipIds.update { it + sessionId }
                    throw e
                }
                failedZipIds.update { it - sessionId }
                zip
            }
        } finally {
            zippingIds.update { it - sessionId }
        }
    }

    suspend fun getZipUri(sessionId: String): Uri {
        val zipFile = zipSession(sessionId)
        return debugLogZipper.getUriForZip(zipFile)
    }

    suspend fun deleteSession(sessionId: String) = fsMutex.withLock {
        // Do NOT call sessions.first() here — deadlock risk with fsMutex
        require(activeSessionId() != sessionId) { "Cannot delete an active recording session" }
        require(sessionId !in zippingIds.value) { "Cannot delete a session that is being compressed" }

        withContext(dispatcherProvider.IO) {
            val (dir, zip) = findSessionFiles(sessionId)
            if (dir?.deleteRecursively() == false) {
                log(TAG, WARN) { "Failed to fully delete session dir: ${dir.path}" }
            }
            if (zip?.delete() == false) {
                log(TAG, WARN) { "Failed to delete session zip: ${zip.path}" }
            }
            // A .zip.tmp left behind by an interrupted zip of this session
            val baseName = sessionId.removePrefix("ext:").removePrefix("cache:")
            recorderManager.getLogDirectories().forEach { parent ->
                val tmp = File(parent, "$baseName.zip.tmp")
                if (deriveSessionId(File(parent, baseName)) == sessionId && tmp.exists() && !tmp.delete()) {
                    log(TAG, WARN) { "Failed to delete session zip temp: ${tmp.path}" }
                }
            }
        }
        failedZipIds.update { it - sessionId }

        log(TAG) { "Deleted session: $sessionId" }
        refresh()
    }

    suspend fun deleteAllSessions() = fsMutex.withLock {
        val activeDir = recorderManager.currentLogDir
        val currentlyZipping = zippingIds.value
        withContext(dispatcherProvider.IO) {
            for (dir in recorderManager.getLogDirectories()) {
                if (!dir.exists()) continue
                for (entry in dir.listFiles() ?: emptyArray()) {
                    if (entry == activeDir) {
                        log(TAG) { "Skipping active session dir: $entry" }
                        continue
                    }
                    val entryId = deriveSessionId(entry)
                    if (entryId in currentlyZipping) {
                        log(TAG) { "Skipping zipping session: $entry" }
                        continue
                    }
                    val deleted = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                    if (!deleted) log(TAG, WARN) { "Failed to delete: ${entry.path}" }
                }
            }
        }
        pendingAutoZips.clear()
        failedZipIds.update { emptySet() }
        log(TAG) { "All stored logs deleted" }
        refresh()
    }

    private fun findSessionFiles(sessionId: String): Pair<File?, File?> {
        val baseName = sessionId.removePrefix("ext:").removePrefix("cache:")
        for (logParent in recorderManager.getLogDirectories()) {
            val dir = File(logParent, baseName)
            val zip = File(logParent, "$baseName.zip")
            val idPrefix = if (logParent.absolutePath.contains("/cache/debug/logs")) "cache:" else "ext:"
            if (idPrefix + baseName == sessionId) {
                val dirExists = dir.exists() && dir.isDirectory
                val zipExists = zip.exists() && zip.isFile
                if (dirExists || zipExists) {
                    return Pair(if (dirExists) dir else null, if (zipExists) zip else null)
                }
            }
        }
        return Pair(null, null)
    }

    companion object {
        private val TAG = logTag("Debug", "Session", "Manager")

        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
            .withZone(ZoneId.systemDefault())

        @VisibleForTesting
        internal fun deriveSessionId(file: File): String {
            val prefix = if (file.absolutePath.contains("/cache/debug/logs")) "cache:" else "ext:"
            return prefix + file.name.removeSuffix(".zip")
        }

        @VisibleForTesting
        internal fun parseCreatedAt(file: File): Instant {
            val name = file.name.removeSuffix(".zip")
            // Format: {pkg}_{versionCode}_{yyyy-MM-dd_HH-mm-ss-SSS}
            // Find the timestamp part: after the second underscore
            val underscoreIndices = name.indices.filter { name[it] == '_' }
            if (underscoreIndices.size >= 2) {
                val timestampPart = name.substring(underscoreIndices[1] + 1)
                try {
                    // java.time.Instant required here — DateTimeFormatter.parse needs a TemporalQuery
                    val parsed = TIMESTAMP_FORMAT.parse(timestampPart, java.time.Instant::from)
                    return Instant.fromEpochMilliseconds(parsed.toEpochMilli())
                } catch (_: Exception) {
                }
            }
            return try {
                val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                Instant.fromEpochMilliseconds(attrs.creationTime().toMillis())
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to read creation time for ${file.name}: ${e.message}" }
                Instant.fromEpochMilliseconds(file.lastModified())
            }
        }

        private fun computeDiskSize(file: File): Long {
            if (!file.exists()) return 0L
            return if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                file.length()
            }
        }

        @VisibleForTesting
        internal fun scanSessions(
            logDirectories: List<File>,
            activeDir: File? = null,
            recordingStartedAt: Instant = Instant.fromEpochMilliseconds(0L),
        ): List<DebugSession> {

            data class RawEntry(val dir: File?, val zip: File?, val parentDir: File)

            val entriesByBaseName = mutableMapOf<String, RawEntry>()

            for (logParent in logDirectories) {
                if (!logParent.exists()) continue
                val files = logParent.listFiles() ?: continue
                for (file in files) {
                    if (file.name.endsWith(".tmp")) continue
                    val baseName = file.name.removeSuffix(".zip")
                    val key = logParent.absolutePath + "/" + baseName
                    val existing = entriesByBaseName[key]
                    if (file.isDirectory) {
                        entriesByBaseName[key] = (existing ?: RawEntry(null, null, logParent)).copy(dir = file)
                    } else if (file.isFile && file.extension == "zip") {
                        entriesByBaseName[key] = (existing ?: RawEntry(null, null, logParent)).copy(zip = file)
                    }
                }
            }

            return entriesByBaseName.map { (key, raw) ->
                val baseName = key.substringAfterLast("/")
                val prefix = if (key.contains("/cache/debug/logs")) "cache:" else "ext:"
                val id = prefix + baseName
                val displayName = baseName

                val dir = raw.dir
                val zip = raw.zip
                val referenceFile = dir ?: zip ?: File(raw.parentDir, baseName)
                val createdAt = parseCreatedAt(referenceFile)

                when {
                    dir != null && dir == activeDir -> {
                        val dirSize = computeDiskSize(dir)
                        DebugSession.Recording(
                            id = id,
                            displayName = displayName,
                            createdAt = createdAt,
                            diskSize = dirSize,
                            path = dir,
                            startedAt = recordingStartedAt,
                        )
                    }

                    dir != null && zip != null -> classifyWithZip(id, displayName, createdAt, dir, zip)

                    dir != null -> classifyOrphan(id, displayName, createdAt, dir)

                    zip != null && zip.exists() -> {
                        if (zip.length() == 0L) {
                            DebugSession.Failed(
                                id = id,
                                displayName = displayName,
                                createdAt = createdAt,
                                diskSize = 0L,
                                path = zip,
                                reason = DebugSession.Failed.Reason.CORRUPT_ZIP,
                            )
                        } else {
                            DebugSession.Ready(
                                id = id,
                                displayName = displayName,
                                createdAt = createdAt,
                                diskSize = zip.length(),
                                logDir = null,
                                zipFile = zip,
                                compressedSize = zip.length(),
                            )
                        }
                    }

                    else -> DebugSession.Failed(
                        id = id,
                        displayName = displayName,
                        createdAt = createdAt,
                        diskSize = 0L,
                        path = File(raw.parentDir, baseName),
                        reason = DebugSession.Failed.Reason.MISSING_LOG,
                    )
                }
            }.sortedWith(compareByDescending<DebugSession> { it.createdAt }.thenBy { it.id })
        }

        private fun classifyWithZip(
            id: String,
            displayName: String,
            createdAt: Instant,
            dir: File,
            zip: File,
        ): DebugSession {
            val coreLog = File(dir, "core.log")
            val dirSize = computeDiskSize(dir)
            val zipValid = zip.exists() && zip.length() > 0
            val totalDiskSize = dirSize + (if (zipValid) zip.length() else 0L)

            return when {
                !coreLog.exists() && zipValid -> DebugSession.Ready(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = zip.length(), logDir = null, zipFile = zip, compressedSize = zip.length(),
                )
                !coreLog.exists() -> DebugSession.Failed(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = dirSize, path = dir, reason = DebugSession.Failed.Reason.MISSING_LOG,
                )
                coreLog.length() == 0L && zipValid -> DebugSession.Ready(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = zip.length(), logDir = null, zipFile = zip, compressedSize = zip.length(),
                )
                coreLog.length() == 0L -> DebugSession.Failed(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = dirSize, path = dir, reason = DebugSession.Failed.Reason.EMPTY_LOG,
                )
                else -> DebugSession.Ready(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = totalDiskSize, logDir = dir, zipFile = if (zipValid) zip else null,
                    compressedSize = if (zipValid) zip.length() else 0L,
                )
            }
        }

        private fun classifyOrphan(
            id: String,
            displayName: String,
            createdAt: Instant,
            dir: File,
        ): DebugSession {
            val coreLog = File(dir, "core.log")
            val dirSize = computeDiskSize(dir)

            return when {
                !coreLog.exists() -> DebugSession.Failed(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = dirSize, path = dir, reason = DebugSession.Failed.Reason.MISSING_LOG,
                )
                coreLog.length() == 0L -> DebugSession.Failed(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = dirSize, path = dir, reason = DebugSession.Failed.Reason.EMPTY_LOG,
                )
                else -> DebugSession.Ready(
                    id = id, displayName = displayName, createdAt = createdAt,
                    diskSize = dirSize, logDir = dir, zipFile = null, compressedSize = 0L,
                )
            }
        }
    }
}
