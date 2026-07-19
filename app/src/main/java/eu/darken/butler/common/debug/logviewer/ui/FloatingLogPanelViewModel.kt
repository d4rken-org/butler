package eu.darken.butler.common.debug.logviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.logviewer.core.LogHistoryRecorder
import eu.darken.butler.common.debug.logviewer.core.LogLine
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.throttleLatest
import eu.darken.butler.common.ui.ViewModel3
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FloatingLogPanelViewModel @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val debugSettings: DebugSettings,
    private val recorder: LogHistoryRecorder,
) : ViewModel3(dispatcherProvider, tag = TAG) {

    private val lifecycleStarted = MutableStateFlow(false)
    private val query = MutableStateFlow("")

    /**
     * Panel-local display filter. Deliberately NOT the recorder's capture floor: that is shared
     * with the Developer workspace LOGS tab and owned by the app-level debug/trace wiring —
     * a panel-level selection must not silently starve the other consumer.
     */
    private val displayPriority = MutableStateFlow(Logging.Priority.DEBUG)

    /**
     * Panel-local pause: a frozen [LogHistoryRecorder.Reading]. Capture continues in the shared
     * buffer (nothing is lost); the delta of [LogHistoryRecorder.Reading.totalLines] counts what
     * arrived since the freeze. A recorder-global pause flag would get stuck if this panel
     * disappeared while paused with another owner keeping the recorder installed.
     */
    private val pausedReading = MutableStateFlow<LogHistoryRecorder.Reading?>(null)

    /** The search match the UI is currently parked on, tracked by [LogLine.id] so ring-buffer drops can't corrupt it. */
    private val currentMatchId = MutableStateFlow<Long?>(null)

    val events = SingleEventFlow<Event>()

    /** Whether the panel is on screen at all: user toggle gated by debug-mode. */
    val isRendered: StateFlow<Boolean> = combine(
        debugSettings.floatingLogVisible.flow,
        debugSettings.isDebugMode.flow,
    ) { visible, debug -> visible && debug }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), false)

    // A single throttled read folds buffer + line counter together, so noisy logging can't churn
    // State recomposition faster than the snapshot cadence.
    private val readings = recorder.changes
        .onStart { emit(Unit) }
        .throttleLatest(SNAPSHOT_THROTTLE_MS.milliseconds)
        .map { recorder.read() }

    val state: StateFlow<State> = combine(
        readings,
        query,
        currentMatchId,
        displayPriority,
        pausedReading,
    ) { live, q, curId, displayPrio, frozen ->
        // Display filter on top of the capture floor: raising the level instantly hides stale
        // lower-priority lines still in the buffer.
        val lines = (frozen ?: live).lines.atLevel(displayPrio)
        val matches = matchesIn(lines, q)
        // Park on the newest match by default; re-park if the current one aged out of the buffer.
        val parkedId = curId?.takeIf { matches.contains(it) } ?: matches.lastOrNull()
        State(
            lines = lines,
            query = q,
            matchCount = matches.size,
            currentOrdinal = parkedId?.let { matches.indexOf(it) + 1 } ?: 0,
            currentMatchLineId = parkedId,
            isPaused = frozen != null,
            pausedNewCount = frozen?.let { (live.totalLines - it.totalLines).toInt() } ?: 0,
            displayPriority = displayPrio,
        )
    }
        .flowOn(dispatcherProvider.Default)
        .stateIn(vmScope, SharingStarted.WhileSubscribed(SHARING_STOP_TIMEOUT_MS), State())

    /** Tracks whether THIS owner currently holds the recorder, so acquire/release stay balanced. */
    private var capturing = false

    init {
        // Capture is active only when rendered AND the host is foregrounded, so a persisted
        // visible=true never leaks a globally-installed logger while backgrounded or after
        // debug-mode is switched off. onCompletion guards against the flow dying (e.g. an upstream
        // throw) leaving the recorder installed.
        combine(
            debugSettings.floatingLogVisible.flow,
            debugSettings.isDebugMode.flow,
            lifecycleStarted,
        ) { visible, debug, started -> visible && debug && started }
            .distinctUntilChanged()
            .onEach { active -> updateCapture(active) }
            .onCompletion { updateCapture(false) }
            .launchIn(vmScope)
    }

    override fun onCleared() {
        updateCapture(false)
        super.onCleared()
    }

    @Synchronized
    private fun updateCapture(active: Boolean) {
        if (active == capturing) return
        capturing = active
        if (active) recorder.acquire() else recorder.release()
    }

    fun setLifecycleStarted(started: Boolean) {
        lifecycleStarted.value = started
    }

    fun setQuery(value: String) {
        query.value = value
        // Match parking happens in the state pipeline: a parked match survives query refinement as
        // long as it still matches, otherwise the pipeline falls back to the newest match. Only a
        // cleared search drops the parked position, so a later new query starts fresh.
        if (value.isBlank()) currentMatchId.value = null
    }

    fun setDisplayPriority(priority: Logging.Priority) {
        displayPriority.value = priority
    }

    fun nextMatch() = stepMatch(forward = true)

    fun prevMatch() = stepMatch(forward = false)

    private fun stepMatch(forward: Boolean) {
        val current = state.value
        val matches = matchesIn(current.lines, current.query)
        if (matches.isEmpty()) return
        val idx = matches.indexOf(current.currentMatchLineId)
        val next = when {
            idx < 0 -> if (forward) 0 else matches.lastIndex
            forward -> (idx + 1).mod(matches.size)
            else -> (idx - 1).mod(matches.size)
        }
        currentMatchId.value = matches[next]
    }

    fun togglePause() {
        pausedReading.value = if (pausedReading.value == null) recorder.read() else null
    }

    fun clearBuffer() {
        recorder.clear()
        // If paused, re-freeze on the now-empty buffer so the panel's own clear takes visible
        // effect while pause stays active.
        pausedReading.value = pausedReading.value?.let { recorder.read() }
        currentMatchId.value = null
    }

    fun copyAll() = launch {
        val visible = state.value.lines
        val truncatedBy = (visible.size - COPY_LINE_CAP).coerceAtLeast(0)
        val text = visible.takeLast(COPY_LINE_CAP).joinToString("\n") { it.render() }
        withContext(dispatcherProvider.Main) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            events.emit(Event.Copied(truncatedBy = truncatedBy))
        }
    }

    fun shareAll() = launch {
        // Always go via a temp file + FileProvider so a large buffer can't blow the binder limit.
        // Own directory: cacheDir/debug/logs is deleted by App's startup cleanup. Timestamped name
        // so a second share can't overwrite a file a receiver hasn't opened yet; older exports are
        // swept opportunistically.
        val visible = state.value.lines
        val intent = withContext(dispatcherProvider.IO) {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            // Sweep only stale exports: a receiver may still hold the FileProvider uri of a recent
            // one, and a concurrent share must not delete a sibling mid-flight.
            val cutoff = System.currentTimeMillis() - EXPORT_MAX_AGE_MS
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val file = File(dir, "logview-${System.currentTimeMillis()}.txt")
            file.bufferedWriter().use { writer ->
                visible.forEach { line ->
                    writer.write(line.render())
                    writer.newLine()
                }
            }
            val uri = FileProvider.getUriForFile(context, "${BuildConfigWrap.APPLICATION_ID}.provider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_SUBJECT, "${BuildConfigWrap.APPLICATION_ID} LogView")
            }
        }
        events.emit(Event.LaunchShare(Intent.createChooser(intent, context.getString(R.string.debug_logview_share_label))))
    }

    fun onShareLaunchFailed(error: Throwable) {
        errorEvents.tryEmit(error)
    }

    fun close() = launch {
        debugSettings.floatingLogVisible.value(false)
    }

    private fun List<LogLine>.atLevel(min: Logging.Priority): List<LogLine> =
        filter { it.priority.intValue >= min.intValue }

    private fun matchesIn(lines: List<LogLine>, q: String): List<Long> =
        if (q.isBlank()) {
            emptyList()
        } else {
            lines.asSequence()
                .filter { it.message.contains(q, ignoreCase = true) }
                .map { it.id }
                .toList()
        }

    sealed interface Event {
        data class LaunchShare(val intent: Intent) : Event
        data class Copied(val truncatedBy: Int) : Event
    }

    data class State(
        val lines: List<LogLine> = emptyList(),
        val query: String = "",
        val matchCount: Int = 0,
        val currentOrdinal: Int = 0,
        val currentMatchLineId: Long? = null,
        val isPaused: Boolean = false,
        val pausedNewCount: Int = 0,
        val displayPriority: Logging.Priority = Logging.Priority.DEBUG,
    )

    companion object {
        private val TAG = logTag("Debug", "LogView", "Floating", "ViewModel")
        private const val SNAPSHOT_THROTTLE_MS = 250L
        private const val SHARING_STOP_TIMEOUT_MS = 5_000L
        private const val CLIP_LABEL = "Butler log"
        private const val SHARE_DIR = "logview_share"
        private const val EXPORT_MAX_AGE_MS = 60L * 60L * 1000L
        const val COPY_LINE_CAP = 1000
    }
}
