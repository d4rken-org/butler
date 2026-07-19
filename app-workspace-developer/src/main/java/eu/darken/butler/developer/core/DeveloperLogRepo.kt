package eu.darken.butler.developer.core

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.logviewer.core.LogHistoryRecorder
import eu.darken.butler.common.flow.throttleLatest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin adapter exposing the shared [LogHistoryRecorder] to the Developer workspace LOGS tab.
 *
 * Ownership is delegated to the recorder's internal ref-counting, so the workspace (owns for its
 * lifetime) and the floating log panel (owns while visible) coexist as independent owners.
 *
 * The throttle sits BEFORE the map: the snapshot+render over the shared buffer must run at most
 * once per interval, not once per log line.
 */
@Singleton
class DeveloperLogRepo @Inject constructor(
    private val recorder: LogHistoryRecorder,
) {

    val logLines: Flow<List<String>> = recorder.changes
        .onStart { emit(Unit) }
        .throttleLatest(100.milliseconds)
        .map { renderTail() }

    val currentLogLines: List<String>
        get() = renderTail()

    private fun renderTail(): List<String> = recorder.snapshot().takeLast(TAB_LINE_CAP).map { it.renderTab() }

    suspend fun install() {
        log(TAG) { "install()" }
        recorder.acquire()
    }

    suspend fun uninstall() {
        log(TAG) { "uninstall()" }
        recorder.release()
    }

    fun clear() {
        recorder.clear()
    }

    companion object {
        private const val TAB_LINE_CAP = 500
        private val TAG = logTag("Developer", "LogRepo")
    }
}
