package eu.darken.butler.common.debug.bugreport

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.BuildWrap
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.FileLogger
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.getPackageInfo
import eu.darken.butler.upgrade.UpgradeDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Captures a [BugReport.Type.RECORDING] report: a continuous log file the user explicitly starts and
 * stops. Installs a [FileLogger] into the [Logging] bus that appends to `report.log` inside the
 * report's directory in the unified store ([BugReportStorage]).
 *
 * `meta.json` is written at START, so even a crash mid-recording leaves a complete report directory;
 * a `.recording` sentinel marks the report as ongoing while the logger is attached. The sentinel
 * doubles as the resume marker: on the next main-process launch, [recoverInterruptedRecording]
 * reattaches to a recording interrupted by process death (force stop, crash, system kill) and keeps
 * appending to its `report.log`, so the reproduction the user was capturing is not silently cut off.
 *
 * Lives in app-common so the bug-report workspace module (which cannot depend on `:app`) can drive
 * recording. Does NOT depend on [BugReportRepo] (the repo observes this recorder, not vice versa).
 */
@Singleton
class BugReportRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val butlerId: ButlerId,
    private val json: Json,
    private val storageLayout: BugReportStorageLayout,
    private val recorderPathPublisher: RecorderPathPublisher,
    // Multibound set, never a single binding: the implementations live in the flavor source sets of
    // :app, and app-common must build (and record) whether or not one was contributed.
    private val upgradeDiagnostics: Set<@JvmSuppressWildcards UpgradeDiagnostics>,
) {

    // Test seam: the bounded reads below run on real dispatchers, so a virtual-time test cannot
    // advance the production bound. Same pattern as BillingCache.cacheTimeoutMs.
    internal var diagnosticsTimeout = DIAGNOSTICS_TIMEOUT

    // Test seams for the two clocks a recording uses: both are real-time reads, so virtual time
    // cannot drive them either. Same pattern as diagnosticsTimeout above.
    internal var wallClock: () -> kotlin.time.Instant = { Clock.System.now() }
    internal var monotonicClock: () -> Long = android.os.SystemClock::elapsedRealtime

    // Test seam for the recovery process gate: the JVM test process name is not the package name.
    internal var processName: () -> String? = { currentProcessName() }

    private val mutex = Mutex()
    private var fileLogger: FileLogger? = null

    /**
     * Monotonic base for the duration heuristic, guarded by [mutex] like [fileLogger]. Deliberately
     * NOT part of the public [State]: no consumer measures duration itself (they all render the wall
     * stamp). A resumed recording gets a fresh base in the new process and waives the minimum
     * instead ([minDurationWaived]) — the pre-death portion cannot be measured monotonically.
     */
    private var startedAtMonotonicMs: Long = 0L

    /**
     * Set for resumed recordings: the session spans a process death, so it either already met the
     * minimum before dying or the death itself is the evidence — warning "too short" right after
     * reopening would be a false positive.
     */
    private var minDurationWaived: Boolean = false

    private val internalState = MutableStateFlow(State())
    val state: StateFlow<State> = internalState.asStateFlow()

    /** Begin a recording. Idempotent (no-op if already recording). Never throws. */
    suspend fun start() = mutex.withLock {
        try {
            if (internalState.value.isRecording) return@withLock

            val now = wallClock()
            // Sampled here, next to the wall stamp and BEFORE the file setup and the per-provider
            // diagnostics loop below. Capturing it at the state commit instead would exclude that
            // setup time from the measured duration, changing what the existing heuristic measures.
            val startedAtMonotonic = monotonicClock()
            val id = "${BugReportStorage.RECORDING_ID_PREFIX}${now.toEpochMilliseconds()}_${Uuid.random().toString().take(8)}"
            val reportDir = File(storageLayout.writeRoot, id)
            reportDir.mkdirs()

            // meta.json first: a crash mid-recording still leaves a complete, surfaceable report.
            val report = buildRecordingReport(id, now)
            File(reportDir, BugReportStorage.META_FILE)
                .writeText(json.encodeToString(BugReport.serializer(), report))

            // report.log next (created before the sentinel — the sentinel never exists without a log).
            val logger = FileLogger(File(reportDir, BugReportStorage.LOG_FILE), worldReadable = false)
            if (!logger.start()) {
                log(TAG, ERROR) { "FileLogger failed to start for $id, aborting recording" }
                runCatching { reportDir.deleteRecursively() }
                return@withLock
            }
            Logging.install(logger)
            fileLogger = logger

            File(reportDir, BugReportStorage.RECORDING_SENTINEL).createNewFile()
            recorderPathPublisher.publish(reportDir.path)

            // Committed before the session infos are logged: those reads take seconds, and until the
            // id is published the finished report directory on disk has nothing marking it as live.
            startedAtMonotonicMs = startedAtMonotonic
            minDurationWaived = false
            internalState.value = State(
                isRecording = true,
                recordingId = id,
                startedAtMs = now.toEpochMilliseconds(),
                currentLogSize = File(reportDir, BugReportStorage.LOG_FILE).length(),
            )
            startLogSizeUpdates(reportDir)

            logSessionInfos()

            log(TAG, INFO) { "Recording started: $id" }
        } catch (e: Throwable) {
            log(TAG, ERROR) { "start() failed: ${e.asLog()}" }
            runCatching { stopInternal() }
        }
    }

    /** Stop only if at least [MIN_RECORDING_MS] elapsed; otherwise leave running and report [StopResult.TooShort]. */
    suspend fun requestStop(): StopResult = mutex.withLock {
        val current = internalState.value
        if (!current.isRecording) return@withLock StopResult.NotRecording
        // Monotonic, immune to wall-clock adjustments mid-recording (NTP sync, the user changing the
        // time). State.startedAtMs stays wall — that is the stamp the banner, the contact form and
        // the bug-report workspace render, and it is not what this heuristic measures.
        val elapsed = monotonicClock() - startedAtMonotonicMs
        if (!minDurationWaived && elapsed < MIN_RECORDING_MS) return@withLock StopResult.TooShort
        val id = current.recordingId
        stopInternal()
        if (id != null) StopResult.Stopped(id) else StopResult.NotRecording
    }

    /** Stop unconditionally (banner force-stop). Never throws. */
    suspend fun forceStop(): StopResult.Stopped? = mutex.withLock {
        val id = internalState.value.recordingId
        stopInternal()
        id?.let { StopResult.Stopped(it) }
    }

    /**
     * Whether [id] is the recording this recorder currently owns. Takes [mutex] rather than reading
     * [state] directly: [start] runs wholly under the lock, so a caller blocks until a start in
     * flight has either published its id or rolled back, instead of observing the window in between.
     */
    internal suspend fun isActiveOrStarting(id: String): Boolean = mutex.withLock {
        internalState.value.recordingId == id
    }

    private fun stopInternal() {
        fileLogger?.let {
            log(TAG, INFO) { "Stopping recording logger: $it" }
            Logging.remove(it)
            it.stop()
        }
        fileLogger = null
        recorderPathPublisher.publish(null)
        startedAtMonotonicMs = 0L
        minDurationWaived = false
        val id = internalState.value.recordingId
        if (id != null) {
            // Every root: a resumed recording can live in the legacy private root. A sentinel that
            // survives a clean stop would resurrect the recording on the next launch (recovery still
            // guards on the log's clean END line, but that is the fallback, not the plan).
            storageLayout.allReportDirs(id).forEach { dir ->
                val sentinel = File(dir, BugReportStorage.RECORDING_SENTINEL)
                try {
                    if (sentinel.exists() && !sentinel.delete()) {
                        log(TAG, ERROR) { "Could not remove recording sentinel: $sentinel" }
                    }
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Could not remove recording sentinel $sentinel: ${e.asLog()}" }
                }
            }
        }
        internalState.value = State()
    }

    private fun startLogSizeUpdates(reportDir: File) {
        appScope.launch(dispatcherProvider.IO) {
            val logFile = File(reportDir, BugReportStorage.LOG_FILE)
            while (isActive) {
                delay(LOG_SIZE_UPDATE_INTERVAL_MS)
                val current = internalState.value
                if (!current.isRecording || current.recordingId != reportDir.name) break
                val size = runCatching { logFile.length() }.getOrDefault(current.currentLogSize)
                internalState.value = current.copy(currentLogSize = size)
            }
        }
    }

    /**
     * Resume the recording interrupted by process death, if there is one: the newest sentinel-bearing
     * recording whose log does NOT end with [FileLogger.END_MARKER] gets its [FileLogger] reattached
     * (append mode, so its `=== BEGIN ===` header marks the process boundary in the log). Any other
     * leftover sentinel is finalized (the dir is already a complete report): a clean-END log means a
     * stop whose sentinel delete failed, and only one recording can ever be live. Recording dirs that
     * never got a `report.log` are deleted.
     *
     * MAIN PROCESS ONLY, fail-closed — the `:isolated` process constructs this class too and must
     * neither resume nor finalize the main process's recording. Runs under [mutex] and skips the
     * currently-active recording id so it can never race [start] and tear down a live recording
     * (e.g. if init cleanup is delayed past a user-initiated start).
     */
    suspend fun recoverInterruptedRecording() = mutex.withLock {
        if (processName() != context.packageName) return@withLock
        val activeId = internalState.value.recordingId

        val candidates = mutableListOf<Pair<File, BugReport>>()
        val seenIds = mutableSetOf<String>()
        storageLayout.roots.forEach { root ->
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                if (dir.name.startsWith(BugReportStorage.TMP_PREFIX)) return@forEach
                if (!dir.name.startsWith(BugReportStorage.RECORDING_ID_PREFIX)) return@forEach
                if (dir.name == activeId) return@forEach
                // First root wins, matching scan(): a sentinel on a shadowed copy must never resume
                // it — logging would append to a copy the report list is not showing.
                val shadowed = !seenIds.add(dir.name)
                val logFile = File(dir, BugReportStorage.LOG_FILE)
                if (!logFile.exists()) {
                    // Died between meta.json and report.log creation — incomplete, never surfaceable.
                    runCatching { dir.deleteRecursively() }
                    log(TAG, WARN) { "Dropped incomplete recording dir: ${dir.name}" }
                    return@forEach
                }
                if (!File(dir, BugReportStorage.RECORDING_SENTINEL).exists()) return@forEach
                val meta = readResumableMeta(dir)
                if (shadowed || meta == null || endsCleanly(logFile)) {
                    finalize(dir)
                } else {
                    candidates += dir to meta
                }
            }
        }

        // A user-initiated start that won the race owns the session — leftovers only get finalized.
        if (activeId != null) {
            candidates.forEach { (dir, _) -> finalize(dir) }
            return@withLock
        }

        // Newest wins; the sort is stable, so between equal stamps the earlier root wins — the same
        // precedence scan() applies when an id exists in both roots.
        val byAge = candidates.sortedByDescending { (_, meta) -> meta.createdAt }
        byAge.drop(1).forEach { (dir, _) -> finalize(dir) }
        byAge.firstOrNull()?.let { (dir, meta) -> resume(dir, meta) }
    }

    /** The recording's metadata, or null if it cannot be safely appended to under [dir]'s name. */
    private fun readResumableMeta(dir: File): BugReport? {
        val meta = try {
            json.decodeFromString(BugReport.serializer(), File(dir, BugReportStorage.META_FILE).readText())
        } catch (e: Exception) {
            log(TAG, WARN) { "Unreadable meta in ${dir.name}: ${e.message}" }
            return null
        }
        if (meta.type != BugReport.Type.RECORDING) return null
        if (meta.id != dir.name) return null
        return meta
    }

    /** Whether the log's last non-blank line is [FileLogger.END_MARKER]; bounded tail read. */
    private fun endsCleanly(logFile: File): Boolean = try {
        java.io.RandomAccessFile(logFile, "r").use { raf ->
            val readSize = minOf(raf.length(), END_PROBE_BYTES)
            raf.seek(raf.length() - readSize)
            val tail = ByteArray(readSize.toInt())
            raf.readFully(tail)
            tail.decodeToString().lineSequence().lastOrNull { it.isNotBlank() } == FileLogger.END_MARKER
        }
    } catch (e: Exception) {
        log(TAG, WARN) { "Could not read log tail of $logFile: ${e.asLog()}" }
        false
    }

    private fun finalize(dir: File) {
        val sentinel = File(dir, BugReportStorage.RECORDING_SENTINEL)
        try {
            if (!sentinel.exists() || sentinel.delete()) {
                log(TAG, WARN) { "Finalized interrupted recording: ${dir.name}" }
            } else {
                log(TAG, ERROR) { "Could not finalize recording, sentinel not deletable: $sentinel" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Could not finalize recording $sentinel: ${e.asLog()}" }
        }
    }

    /** Reattach to an interrupted recording. Caller holds [mutex]. */
    private suspend fun resume(reportDir: File, meta: BugReport) {
        try {
            val logFile = File(reportDir, BugReportStorage.LOG_FILE)
            val logger = FileLogger(logFile, worldReadable = false)
            if (!logger.start()) {
                log(TAG, ERROR) { "FileLogger could not reattach for ${meta.id}, finalizing instead" }
                finalize(reportDir)
                return
            }
            Logging.install(logger)
            fileLogger = logger
            recorderPathPublisher.publish(reportDir.path)

            startedAtMonotonicMs = monotonicClock()
            minDurationWaived = true
            internalState.value = State(
                isRecording = true,
                recordingId = meta.id,
                startedAtMs = meta.createdAt.toEpochMilliseconds(),
                currentLogSize = logFile.length(),
            )
            startLogSizeUpdates(reportDir)

            log(TAG, INFO) { "Recording resumed after process death: ${meta.id}" }
            logSessionInfos()
        } catch (e: Throwable) {
            log(TAG, ERROR) { "resume() failed for ${meta.id}: ${e.asLog()}" }
            runCatching { stopInternal() }
        }
    }

    private fun buildRecordingReport(id: String, now: kotlin.time.Instant): BugReport = BugReport(
        id = id,
        createdAt = now,
        type = BugReport.Type.RECORDING,
        appVersion = safeField { BuildConfigWrap.VERSION_DESCRIPTION },
        deviceFingerprint = safeField { Build.FINGERPRINT },
        apiLevel = safeField { Build.VERSION.SDK_INT.toString() },
        flavor = safeField { BuildConfigWrap.FLAVOR.name },
        buildType = safeField { BuildConfigWrap.BUILD_TYPE.name },
        installId = safeField { butlerId.id },
        locale = safeField { Resources.getSystem().configuration.locales.toLanguageTags() },
    )

    private suspend fun logSessionInfos() {
        runCatching {
            val pkgInfo = context.getPackageInfo()
            log(TAG, INFO) { "APILEVEL: ${BuildWrap.VERSION.SDK_INT}" }
            log(TAG, INFO) { "Build.FINGERPRINT: ${BuildWrap.FINGERPRINT}" }
            log(TAG, INFO) { "Build.MANUFACTURER: ${Build.MANUFACTURER}" }
            log(TAG, INFO) { "Build.BRAND: ${Build.BRAND}" }
            log(TAG, INFO) { "Build.PRODUCT: ${Build.PRODUCT}" }
            val versionInfo = "${pkgInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pkgInfo)})"
            log(TAG, INFO) { "App: ${context.packageName} - $versionInfo" }
            log(TAG, INFO) { "Build: ${BuildConfigWrap.FLAVOR}-${BuildConfigWrap.BUILD_TYPE}" }
            log(TAG, INFO) { "Install ID: ${butlerId.id}" }
            log(TAG, INFO) { "App locales: ${Resources.getSystem().configuration.locales}" }
        }
        logUpgradeDiagnostics()
    }

    /**
     * Entitlement diagnostics for the header: billing complaints arrive as debug recordings, so
     * having the local purchase cache and the lifetime Pro-state history in the log saves a support
     * round-trip.
     *
     * Each provider is isolated: a failing or hanging one must not stop the recording from starting,
     * and must not suppress the others' independent evidence.
     */
    private suspend fun logUpgradeDiagnostics() {
        upgradeDiagnostics.forEach { diagnostics ->
            try {
                val read = withTimeoutOrNull(diagnosticsTimeout) { HeaderRead(diagnostics.debugInfo()) }
                if (read == null) {
                    log(TAG, WARN) {
                        "Upgrade diagnostics unavailable ($diagnostics), read did not finish within $diagnosticsTimeout"
                    }
                } else if (read.value == null) {
                    log(TAG) { "No upgrade diagnostics from $diagnostics" }
                } else {
                    log(TAG, INFO) { "Upgrade diagnostics: ${read.value}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "Upgrade diagnostics unavailable ($diagnostics): ${e.asLog()}" }
            }
        }
    }

    /**
     * Completion marker for a diagnostics read: tells a source that legitimately has nothing to
     * report (no diagnostics on FOSS) apart from one that never answered within the bound. Without
     * it both arrive as a bare `null` and the log can't say which happened.
     */
    private class HeaderRead<T>(val value: T)

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val pid = android.os.Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private inline fun safeField(block: () -> String): String = try {
        block()
    } catch (t: Throwable) {
        "unavailable: ${t.message}"
    }

    data class State(
        val isRecording: Boolean = false,
        val recordingId: String? = null,
        val startedAtMs: Long = 0L,
        val currentLogSize: Long = 0L,
    )

    sealed interface StopResult {
        data object NotRecording : StopResult
        data object TooShort : StopResult
        data class Stopped(val reportId: String) : StopResult
    }

    companion object {
        private val TAG = logTag("Debug", "BugReport", "Recorder")
        /**
         * Duration heuristic for "did you forget to reproduce the issue?". A recording stopped this
         * quickly usually contains nothing but the recorder starting and stopping, which costs a
         * support round-trip to re-request.
         *
         * It stays a prompt because short recordings can be perfectly valid: a crash is logged and
         * flushed immediately, so the reproduction is already on disk. The [StopResult.TooShort]
         * consumers — the support contact form, the recording banner and the bug-report workspace
         * dialog — turn it into their short-recording warning, and its "stop anyway" answer goes
         * through [forceStop], which has no duration check.
         */
        const val MIN_RECORDING_MS = 10_000L
        private const val LOG_SIZE_UPDATE_INTERVAL_MS = 5_000L

        // Diagnostics read local storage only; a longer wait would just delay the recording start.
        private val DIAGNOSTICS_TIMEOUT = 5.seconds

        // Comfortably covers the END marker plus a torn trailing line.
        private const val END_PROBE_BYTES = 4096L
    }
}
