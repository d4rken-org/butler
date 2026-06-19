package eu.darken.butler.common.debug.bugreport

import eu.darken.butler.common.serialization.InstantSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A locally-stored bug report: metadata + (for crashes/manual reports) the error that triggered it.
 * The accompanying log trail is stored next to it on disk (`report.log`) rather than inline, so it
 * can be large.
 *
 * Metadata fields are plain strings so collection can degrade to `"unavailable: …"` without aborting
 * the report (see [BugReportRepo]). The error fields are nullable because a [Type.RECORDING] report
 * has no triggering exception.
 */
@Serializable
data class BugReport(
    val id: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val type: Type,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val stackTrace: String? = null,
    val threadName: String? = null,
    val appVersion: String,
    val deviceFingerprint: String,
    val apiLevel: String,
    val flavor: String,
    val buildType: String,
    val installId: String,
    val locale: String,
) {
    enum class Type {
        /** Automatic, captured synchronously from the uncaught-exception handler. */
        CRASH,

        /** Manual ring-buffer snapshot via [eu.darken.butler.common.debug.Bugs.report]. */
        REPORTED,

        /** Manual, continuous file capture started/stopped by the user. */
        RECORDING,
    }
}

/**
 * A stored report plus the filesystem-derived state the UI needs.
 *
 * [isOngoingRecording] and [recordingLogSize] are never persisted — they are derived in
 * [BugReportRepo.scan] from the live [BugReportRecorder] state + the `.recording` sentinel.
 */
data class BugReportInfo(
    val report: BugReport,
    val isSeen: Boolean,
    val isOngoingRecording: Boolean = false,
    val recordingLogSize: Long = 0L,
) {
    val id: String get() = report.id
}
