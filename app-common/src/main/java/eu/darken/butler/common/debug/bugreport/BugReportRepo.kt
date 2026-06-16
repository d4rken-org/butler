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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val butlerId: ButlerId,
    private val json: Json,
) {

    private val reportsDir = File(context.filesDir, REPORTS_DIRNAME)
    private val shareDir = File(context.cacheDir, SHARE_DIRNAME)
    private val refreshTrigger = MutableStateFlow(0)

    val reports: Flow<List<BugReportInfo>> = refreshTrigger
        .map { withContext(dispatcherProvider.IO) { scan() } }

    val hasUnseenCrashes: Flow<Boolean> = reports.map { list ->
        list.any { it.report.type == BugReport.Type.CRASH && !it.isSeen }
    }

    init {
        appScope.launch(dispatcherProvider.IO) {
            try {
                reapStaleTempDirs()
                prune()
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
            val report = buildReport(throwable, thread, BugReport.Type.CRASH)
            writeReport(report, ringLogBuffer.snapshot())
        } catch (_: Throwable) {
        }
    }

    /** Async, silent capture for manual [eu.darken.butler.common.debug.Bugs.report] calls. */
    fun captureReport(throwable: Throwable) {
        val snapshot = ringLogBuffer.snapshot()
        appScope.launch(dispatcherProvider.IO) {
            try {
                val report = buildReport(throwable, Thread.currentThread(), BugReport.Type.REPORTED)
                writeReport(report, snapshot)
                prune()
                refresh()
            } catch (e: Exception) {
                log(TAG, ERROR) { "captureReport failed: ${e.asLog()}" }
            }
        }
    }

    suspend fun markSeen(id: String) = withContext(dispatcherProvider.IO) {
        val dir = File(reportsDir, id)
        if (dir.isDirectory) {
            val marker = File(dir, SEEN_MARKER)
            if (!marker.exists()) runCatching { marker.createNewFile() }
        }
        refresh()
    }

    suspend fun delete(id: String) = withContext(dispatcherProvider.IO) {
        File(reportsDir, id).deleteRecursively()
        File(shareDir, "$id.zip").delete()
        refresh()
    }

    suspend fun deleteAll() = withContext(dispatcherProvider.IO) {
        reportsDir.deleteRecursively()
        shareDir.deleteRecursively()
        refresh()
    }

    /** Full log trail for the detail view; loaded on demand because it can be large. */
    suspend fun readLog(id: String): String = withContext(dispatcherProvider.IO) {
        File(File(reportsDir, id), LOG_FILE).takeIf { it.exists() }?.readText() ?: ""
    }

    /**
     * Build a bare `ACTION_SEND` intent for the report's zip. The UI layer wraps it in a localized
     * chooser. The zip is (re)created under the cache dir; [FLAG_GRANT_READ_URI_PERMISSION] + clipData
     * grant the receiving app read access.
     */
    suspend fun buildShareIntent(id: String): Intent = withContext(dispatcherProvider.IO) {
        val reportDir = File(reportsDir, id)
        val files = listOf(File(reportDir, META_FILE), File(reportDir, LOG_FILE)).filter { it.exists() }
        require(files.isNotEmpty()) { "No report files for $id" }

        if (!shareDir.exists()) shareDir.mkdirs()
        val zip = File(shareDir, "$id.zip")
        Zipper().zip(files.map { it.path }, zip.path)

        val uri = FileProvider.getUriForFile(context, "${BuildConfigWrap.APPLICATION_ID}.provider", zip)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun refresh() {
        refreshTrigger.update { it + 1 }
    }

    private fun buildReport(throwable: Throwable, thread: Thread, type: BugReport.Type): BugReport {
        val now = Clock.System.now()
        return BugReport(
            id = "${type.name.lowercase()}_${now.toEpochMilliseconds()}_${Uuid.random().toString().take(8)}",
            createdAt = now,
            type = type,
            errorClass = safeField { throwable.javaClass.name },
            errorMessage = safeField { throwable.message ?: throwable.javaClass.simpleName },
            stackTrace = safeField { throwable.asLog() },
            threadName = safeField { thread.name },
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
        val tmpDir = File(reportsDir, "$TMP_PREFIX${report.id}")
        val finalDir = File(reportsDir, report.id)
        try {
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            File(tmpDir, META_FILE).writeText(json.encodeToString(BugReport.serializer(), report))
            File(tmpDir, LOG_FILE).writeText(logSnapshot)
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

    private fun scan(): List<BugReportInfo> {
        val children = reportsDir.listFiles() ?: return emptyList()
        return children.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            // Skip (never delete) in-progress temp dirs — deleting here would race a concurrent
            // write. Stale temp dirs are reaped on init via reapStaleTempDirs().
            if (dir.name.startsWith(TMP_PREFIX)) return@mapNotNull null
            val meta = File(dir, META_FILE)
            val logFile = File(dir, LOG_FILE)
            // Require both files so a partially-written/copied dir is never treated as complete.
            if (!meta.exists() || !logFile.exists()) return@mapNotNull null
            val report = try {
                json.decodeFromString(BugReport.serializer(), meta.readText())
            } catch (e: Exception) {
                log(TAG, WARN) { "Skipping unreadable report ${dir.name}: ${e.message}" }
                return@mapNotNull null
            }
            // id doubles as the storage path for delete/prune/share — reject any mismatch.
            if (report.id != dir.name) {
                log(TAG, WARN) { "Skipping report with id/dir mismatch: ${report.id} vs ${dir.name}" }
                return@mapNotNull null
            }
            BugReportInfo(report = report, isSeen = File(dir, SEEN_MARKER).exists())
        }.sortedByDescending { it.report.createdAt }
    }

    /** Remove temp dirs left by interrupted writes, but only past the write grace window. */
    private fun reapStaleTempDirs() {
        val now = System.currentTimeMillis()
        reportsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory &&
                dir.name.startsWith(TMP_PREFIX) &&
                now - dir.lastModified() > TMP_GRACE_MS
            ) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    private fun prune() {
        val valid = scan()
        if (valid.size <= MAX_REPORTS) return
        valid.drop(MAX_REPORTS).forEach { stale ->
            File(reportsDir, stale.id).deleteRecursively()
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
        private const val REPORTS_DIRNAME = "bugreports"
        private const val SHARE_DIRNAME = "bugreports_share"
        private const val TMP_PREFIX = ".tmp-"
        private const val META_FILE = "meta.json"
        private const val LOG_FILE = "report.log"
        private const val SEEN_MARKER = ".seen"
        private const val MAX_REPORTS = 25
        private const val TMP_GRACE_MS = 60_000L
    }
}
