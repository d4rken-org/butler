package eu.darken.butler.common.debug.recorder.core

import java.io.File
import kotlin.time.Instant

sealed interface DebugSession {
    data class Recording(
        val logDir: File,
        val startTime: Instant,
        val currentSize: Long,
    ) : DebugSession

    data class Completed(
        val zipFile: File,
        val zipSize: Long,
    ) : DebugSession

    data class Failed(
        val sessionDir: File,
        val dirSize: Long,
    ) : DebugSession
}
