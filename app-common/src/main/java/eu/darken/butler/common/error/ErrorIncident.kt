package eu.darken.butler.common.error

import java.io.File
import kotlin.time.Instant

/**
 * Everything about an error that is only true at the moment it happens, captured then rather than
 * when the user decides to share it: the throwable, the state around it, and the log trail leading
 * up to it (spooled to [logFile], because the in-memory ring buffer keeps evicting).
 *
 * @param occurredAtIsApproximate [occurredAt] is not when the failure happened: nothing was frozen
 *        while it was live, so this is when sharing it was requested instead.
 * @param logFile null when the spool write failed; a report is still worth sharing without it.
 */
data class ErrorIncident(
    val incidentId: String,
    val occurredAt: Instant,
    val occurredAtIsApproximate: Boolean,
    val error: Throwable,
    val context: Map<String, String>,
    val logFile: File?,
)
