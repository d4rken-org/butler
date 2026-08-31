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
 * a `.recording` sentinel marks the report as ongoing while the logger is attached. On the next
 * launch, [sweepOrphanedSentinels] finalizes any interrupted recording (no resume).
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

    private val mutex = Mutex()
    private var fileLogger: FileLogger? = null

    /**
     * Monotonic base for the duration heuristic, guarded by [mutex] like [fileLogger]. Deliberately
     * NOT part of the public [State]: no consumer measures duration itself (they all render the wall
     * stamp), and there is no resume — an interrupted recording is finalized by
     * [sweepOrphanedSentinels], never continued, so no monotonic base ever has to survive a process.
     */
    private var startedAtMonotonicMs: Long = 0L

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

            logSessionInfos()

            startedAtMonotonicMs = startedAtMonotonic
            internalState.value = State(
                isRecording = true,
                recordingId = id,
                startedAtMs = now.toEpochMilliseconds(),
                currentLogSize = File(reportDir, BugReportStorage.LOG_FILE).length(),
            )
            startLogSizeUpdates(reportDir)
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
        if (elapsed < MIN_RECORDING_MS) return@withLock StopResult.TooShort
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

    private fun stopInternal() {
        fileLogger?.let {
            log(TAG, INFO) { "Stopping recording logger: $it" }
            Logging.remove(it)
            it.stop()
        }
        fileLogger = null
        startedAtMonotonicMs = 0L
        val id = internalState.value.recordingId
        if (id != null) {
            runCatching { File(File(storageLayout.writeRoot, id), BugReportStorage.RECORDING_SENTINEL).delete() }
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
     * Finalize any recording interrupted by process death: drop `.recording` sentinels (the dir is
     * already a complete report) and delete incomplete recording dirs that never got a `report.log`.
     *
     * MAIN PROCESS ONLY — an isolated process must never finalize the main process's live recording.
     * Runs under [mutex] and skips the currently-active recording id so it can never race [start] and
     * tear down a live recording (e.g. if init cleanup is delayed past a user-initiated start).
     */
    suspend fun sweepOrphanedSentinels() = mutex.withLock {
        if (!isMainProcess()) return@withLock
        val activeId = internalState.value.recordingId
        // Every root: a sentinel left behind by a version that still wrote to the private root would
        // otherwise stay forever, and a sentinel-bearing directory is skipped by deleteAll().
        storageLayout.roots.forEach { root ->
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                if (dir.name.startsWith(BugReportStorage.TMP_PREFIX)) return@forEach
                if (!dir.name.startsWith(BugReportStorage.RECORDING_ID_PREFIX)) return@forEach
                if (dir.name == activeId) return@forEach
                val logFile = File(dir, BugReportStorage.LOG_FILE)
                if (logFile.exists()) {
                    val sentinel = File(dir, BugReportStorage.RECORDING_SENTINEL)
                    if (sentinel.exists()) {
                        runCatching { sentinel.delete() }
                        log(TAG, WARN) { "Finalized interrupted recording: ${dir.name}" }
                    }
                } else {
                    // Died between meta.json and report.log creation — incomplete, never surfaceable.
                    runCatching { dir.deleteRecursively() }
                    log(TAG, WARN) { "Dropped incomplete recording dir: ${dir.name}" }
                }
            }
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

    private fun isMainProcess(): Boolean {
        val name = currentProcessName() ?: return true // best-effort: assume main if undetermined
        return name == context.packageName
    }

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
    }
}
