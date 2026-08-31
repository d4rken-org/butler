package eu.darken.butler.common.debug.bugreport

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.compression.Zipper
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.RingLogBuffer
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Stores user-supported bug reports on-device: uncaught crashes (captured synchronously from the
 * crash handler) and manual [eu.darken.butler.common.debug.Bugs] reports. Each report is a directory
 * holding `meta.json` ([BugReport]) + `report.log` (a [RingLogBuffer] snapshot). Reports can be
 * shared as a zip after explicit user consent.
 *
 * Lives in app-common (not :app) so the bug-report workspace module — which cannot depend on :app —
 * can read it.
 */
@Singleton
class BugReportRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val ringLogBuffer: RingLogBuffer,
    private val bugReportRecorder: BugReportRecorder,
    private val butlerId: ButlerId,
    private val json: Json,
    private val storageLayout: BugReportStorageLayout,
) {

    private val shareDir = BugReportStorage.shareDir(context)
    private val refreshTrigger = MutableStateFlow(0)

    // Re-scans on manual triggers AND on recording start/stop transitions. We key on recordingId
    // (distinct) rather than the whole recorder state so the periodic live-size ticks do NOT trigger a
    // disk re-scan — the live size reaches the UI directly via the workspace VM's combine on the
    // recorder state, not through this scan.
    val reports: Flow<List<BugReportInfo>> = combine(
        refreshTrigger,
        bugReportRecorder.state.map { it.recordingId }.distinctUntilChanged(),
    ) { _, _ -> withContext(dispatcherProvider.IO) { scan() } }

    val hasUnseenCrashes: Flow<Boolean> = reports.map { list ->
        list.any { it.report.type == BugReport.Type.CRASH && !it.isSeen }
    }

    init {
        appScope.launch(dispatcherProvider.IO) {
            try {
                reapStaleTempDirs()
                bugReportRecorder.sweepOrphanedSentinels()
                prune()
                // Cleanup may have dropped sentinels/dirs after an early scan already ran; make sure
                // collectors re-scan so an interrupted recording stops showing as ongoing.
                refresh()
            } catch (e: Exception) {
                log(TAG, WARN) { "Initial cleanup failed: ${e.asLog()}" }
            }
        }
    }

    /**
     * Synchronous crash capture, called from the uncaught-exception handler on the dying thread.
     * No coroutines (the process is going away). Best-effort: an OOM may prevent the write. Metadata
     * is collected field-by-field via [safeField] so a single failure (e.g. [ButlerId.id] throwing
     * on a corrupt install-id file) never aborts the report.
     */
    fun captureCrashBlocking(throwable: Throwable, thread: Thread) {
        // Belt-and-suspenders: even snapshot()/buildReport() allocation can fail under OOM. The caller
        // also guards this, but the method itself must never throw.
        try {
            val report = buildReport(throwable, thread.name, BugReport.Type.CRASH)
            writeReport(report, ringLogBuffer.snapshot())
        } catch (_: Throwable) {
        }
    }

    /** Async, silent capture for manual [eu.darken.butler.common.debug.Bugs.report] calls. */
    fun captureReport(throwable: Throwable) {
        val snapshot = ringLogBuffer.snapshot()
        // The name, not the Thread: the report is built on a dispatcher thread, and a Thread handed
        // over is also free to be renamed before buildReport reads it.
        val threadName = Thread.currentThread().name
        appScope.launch(dispatcherProvider.IO) {
            try {
                val report = buildReport(throwable, threadName, BugReport.Type.REPORTED)
                writeReport(report, snapshot)
                prune()
                refresh()
            } catch (e: Exception) {
                log(TAG, ERROR) { "captureReport failed: ${e.asLog()}" }
            }
        }
    }

    suspend fun markSeen(id: String) = withContext(dispatcherProvider.IO) {
        resolveReportDir(id)?.let { dir ->
            val marker = File(dir, BugReportStorage.SEEN_MARKER)
            if (!marker.exists()) runCatching { marker.createNewFile() }
        }
        refresh()
    }

    suspend fun delete(id: String) = withContext(dispatcherProvider.IO) {
        require(id != bugReportRecorder.state.value.recordingId) { "Cannot delete an active recording" }
        // Every copy, not just the one scan() lists: dropping only the listed copy would let the
        // shadowed one take its place in the very next scan.
        storageLayout.allReportDirs(id).forEach { it.deleteRecursively() }
        File(shareDir, "$id.zip").delete()
        refresh()
    }

    suspend fun deleteAll() = withContext(dispatcherProvider.IO) {
        // Skip the active recording (its report.log is held open by FileLogger). We guard on both the
        // published recordingId AND a live `.recording` sentinel: start() creates the dir+sentinel
        // before publishing the id to recorder state, so the sentinel check also covers a recording
        // that is mid-start when delete-all fires.
        val activeId = bugReportRecorder.state.value.recordingId
        storageLayout.roots.forEach { root ->
            root.listFiles()?.forEach { entry ->
                if (entry.name == activeId) return@forEach
                if (entry.isDirectory && File(entry, BugReportStorage.RECORDING_SENTINEL).exists()) return@forEach
                if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            }
        }
        shareDir.listFiles()?.forEach { zip ->
            if (activeId != null && zip.name == "$activeId.zip") return@forEach
            zip.delete()
        }
        refresh()
    }

    /** Full log trail; loaded on demand because it can be large. Kept for callers needing the whole log. */
    suspend fun readLog(id: String): String = withContext(dispatcherProvider.IO) {
        val dir = resolveReportDir(id) ?: return@withContext ""
        File(dir, BugReportStorage.LOG_FILE).takeIf { it.exists() }?.readText() ?: ""
    }

    /**
     * Bounded tail of a report's log for the detail view: streams the file line-by-line and keeps only
     * the last [maxLines] in a ring buffer plus a total-line count — never holds the whole file or a
     * full `split` list in memory, so a multi-MB recording costs a bounded amount regardless of size.
     */
    suspend fun readLogTail(id: String, maxLines: Int): LogTail = withContext(dispatcherProvider.IO) {
        require(maxLines > 0) { "maxLines must be > 0, was $maxLines" }
        val dir = resolveReportDir(id) ?: return@withContext LogTail(emptyList(), 0)
        val file = File(dir, BugReportStorage.LOG_FILE)
        if (!file.exists()) return@withContext LogTail(emptyList(), 0)
        val ring = ArrayDeque<String>(maxLines)
        var total = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                total++
                if (ring.size == maxLines) ring.removeFirst()
                ring.addLast(line)
            }
        }
        LogTail(lines = ring.toList(), totalLines = total)
    }

    /** Result of [readLogTail]: the tail [lines] plus the [totalLines] count for "last N of M" framing. */
    data class LogTail(
        val lines: List<String>,
        val totalLines: Int,
    )

    /**
     * (Re)create the report's share zip under the cache dir and return a [FileProvider] uri. Used both
     * by [buildShareIntent] and by the support contact form when attaching a report to an email.
     */
    suspend fun buildShareUri(id: String): Uri = withContext(dispatcherProvider.IO) {
        require(id != bugReportRecorder.state.value.recordingId) { "Cannot share an active recording" }
        val reportDir = resolveReportDir(id)
        val files = reportDir?.let { listOf(File(it, BugReportStorage.META_FILE), File(it, BugReportStorage.LOG_FILE)) }
            ?.filter { it.exists() }
            ?: emptyList()
        require(files.isNotEmpty()) { "No report files for $id" }

        if (!shareDir.exists()) shareDir.mkdirs()
        val zip = File(shareDir, "$id.zip")
        Zipper().zip(files.map { it.path }, zip.path)

        FileProvider.getUriForFile(context, "${BuildConfigWrap.APPLICATION_ID}.provider", zip)
    }

    /**
     * Build a bare `ACTION_SEND` intent for the report's zip. The UI layer wraps it in a localized
     * chooser. [FLAG_GRANT_READ_URI_PERMISSION] + clipData grant the receiving app read access.
     */
    suspend fun buildShareIntent(id: String): Intent {
        val uri = buildShareUri(id)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun refresh() {
        refreshTrigger.update { it + 1 }
    }

    private fun buildReport(throwable: Throwable, threadName: String, type: BugReport.Type): BugReport {
        val now = Clock.System.now()
        return BugReport(
            id = "${type.name.lowercase()}_${now.toEpochMilliseconds()}_${Uuid.random().toString().take(8)}",
            createdAt = now,
            type = type,
            errorClass = safeField { throwable.javaClass.name },
            errorMessage = safeField { throwable.message ?: throwable.javaClass.simpleName },
            stackTrace = safeField { throwable.asLog() },
            threadName = safeField { threadName },
            appVersion = safeField { BuildConfigWrap.VERSION_DESCRIPTION },
            deviceFingerprint = safeField { Build.FINGERPRINT },
            apiLevel = safeField { Build.VERSION.SDK_INT.toString() },
            flavor = safeField { BuildConfigWrap.FLAVOR.name },
            buildType = safeField { BuildConfigWrap.BUILD_TYPE.name },
            installId = safeField { butlerId.id },
            locale = safeField { Resources.getSystem().configuration.locales.toLanguageTags() },
        )
    }

    /** Two-phase write: build under `.tmp-<id>`, then atomically rename so the scanner never sees a partial dir. */
    private fun writeReport(report: BugReport, logSnapshot: String) {
        val writeRoot = storageLayout.writeRoot
        val tmpDir = File(writeRoot, "${BugReportStorage.TMP_PREFIX}${report.id}")
        val finalDir = File(writeRoot, report.id)
        try {
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            File(tmpDir, BugReportStorage.META_FILE).writeText(json.encodeToString(BugReport.serializer(), report))
            File(tmpDir, BugReportStorage.LOG_FILE).writeText(logSnapshot)
            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                tmpDir.copyRecursively(finalDir, overwrite = true)
                tmpDir.deleteRecursively()
            }
        } catch (t: Throwable) {
            runCatching { tmpDir.deleteRecursively() }
            // Best-effort; never propagate (the crash path must still delegate to the old handler).
            log(TAG, ERROR) { "writeReport failed for ${report.id}: ${t.asLog()}" }
        }
    }

    /**
     * The copy of [id] that [scan] surfaced: the first root holding a valid report directory. A
     * corrupt external copy must not shadow the readable private one the list is showing.
     */
    private fun resolveReportDir(id: String): File? = storageLayout
        .allReportDirs(id)
        .firstOrNull { readReport(it) != null }

    /** Parses a report directory, or null if it is a temp dir, incomplete, unreadable or mislabelled. */
    private fun readReport(dir: File): BugReport? {
        if (!dir.isDirectory) return null
        // Skip (never delete) in-progress temp dirs — deleting here would race a concurrent
        // write. Stale temp dirs are reaped on init via reapStaleTempDirs().
        if (dir.name.startsWith(BugReportStorage.TMP_PREFIX)) return null
        val meta = File(dir, BugReportStorage.META_FILE)
        // Require both files so a partially-written/copied dir is never treated as complete.
        // (An ongoing recording always has both: meta.json + report.log are created before the
        // .recording sentinel.)
        if (!meta.exists() || !File(dir, BugReportStorage.LOG_FILE).exists()) return null
        val report = try {
            json.decodeFromString(BugReport.serializer(), meta.readText())
        } catch (e: Exception) {
            log(TAG, WARN) { "Skipping unreadable report ${dir.name}: ${e.message}" }
            return null
        }
        // id doubles as the storage path for delete/prune/share — reject any mismatch.
        if (report.id != dir.name) {
            log(TAG, WARN) { "Skipping report with id/dir mismatch: ${report.id} vs ${dir.name}" }
            return null
        }
        return report
    }

    private fun scan(): List<BugReportInfo> {
        // Keyed by id: the same report can exist under both roots, and two entries with the same key
        // would crash the workspace list. First root wins, matching resolveReportDir().
        val byId = LinkedHashMap<String, BugReportInfo>()
        storageLayout.roots.forEach { root ->
            root.listFiles()?.forEach { dir ->
                val report = readReport(dir) ?: return@forEach
                if (byId.containsKey(report.id)) {
                    log(TAG, WARN) { "Duplicate report id ${report.id}, ignoring the copy in ${dir.path}" }
                    return@forEach
                }
                val logFile = File(dir, BugReportStorage.LOG_FILE)
                // "Ongoing" is tied to the live recorder, not just the sentinel: a recording interrupted
                // by process death keeps a stale sentinel until the init sweep clears it, but it is a
                // complete, surfaceable report — so it shows as a normal report immediately instead of
                // vanishing from the list during the brief startup-cleanup window.
                val isOngoing = File(dir, BugReportStorage.RECORDING_SENTINEL).exists() &&
                    dir.name == bugReportRecorder.state.value.recordingId
                byId[report.id] = BugReportInfo(
                    report = report,
                    // Ongoing recordings are implicitly "seen" — they never count as an unseen crash.
                    isSeen = isOngoing || File(dir, BugReportStorage.SEEN_MARKER).exists(),
                    isOngoingRecording = isOngoing,
                    recordingLogSize = if (isOngoing) logFile.length() else 0L,
                    logSizeBytes = logFile.length(),
                )
            }
        }
        return byId.values.sortedByDescending { it.report.createdAt }
    }

    /** Remove temp dirs left by interrupted writes, but only past the write grace window. */
    private fun reapStaleTempDirs() {
        val now = System.currentTimeMillis()
        storageLayout.roots.forEach { root ->
            root.listFiles()?.forEach { dir ->
                if (dir.isDirectory &&
                    dir.name.startsWith(BugReportStorage.TMP_PREFIX) &&
                    now - dir.lastModified() > TMP_GRACE_MS
                ) {
                    runCatching { dir.deleteRecursively() }
                }
            }
        }
    }

    private fun prune() {
        // Never prune the active recording, even if it would otherwise be the oldest.
        val valid = scan().filter { !it.isOngoingRecording }
        if (valid.size <= MAX_REPORTS) return
        valid.drop(MAX_REPORTS).forEach { stale ->
            storageLayout.allReportDirs(stale.id).forEach { it.deleteRecursively() }
            File(shareDir, "${stale.id}.zip").delete()
            log(TAG) { "Pruned old report: ${stale.id}" }
        }
    }

    private inline fun safeField(block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        "unavailable: ${t.message}"
    }

    companion object {
        private val TAG = logTag("Debug", "BugReport", "Repo")
        private const val MAX_REPORTS = 25
        private const val TMP_GRACE_MS = 60_000L
    }
}
