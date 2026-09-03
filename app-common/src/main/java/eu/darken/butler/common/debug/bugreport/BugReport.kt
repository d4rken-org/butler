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
    /** User-set name. Purely cosmetic: [id] stays the storage key and is never derived from this. */
    val label: String? = null,
) {
    enum class Type {
        /** Automatic, captured synchronously from the uncaught-exception handler. */
        CRASH,

        /** Manual ring-buffer snapshot via [eu.darken.butler.common.debug.Bugs.report]. */
        REPORTED,

        /** Manual, continuous file capture started/stopped by the user. */
        RECORDING,
    }

    companion object {
        /**
         * Longest [label] that survives [normalizeLabel]. The UI caps input at the same value to give
         * immediate feedback while typing; [normalizeLabel] stays authoritative.
         */
        const val MAX_LABEL_LENGTH = 128

        /**
         * Normalized [label], or null when the input clears it: whitespace runs (including NBSP and
         * line separators) collapse to a single space, control characters are dropped, the result is
         * trimmed, capped at [MAX_LABEL_LENGTH] and blank == clear.
         */
        fun normalizeLabel(raw: String?): String? = raw
            ?.collapseWhitespace()
            ?.takeCodePoints(MAX_LABEL_LENGTH)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Whitespace runs become a single space, ISO control characters are dropped, and the result carries
 * no leading or trailing space. `" a \n b "` becomes `"a b"`.
 */
private fun String.collapseWhitespace(): String = buildString(length) {
    var pendingSpace = false
    this@collapseWhitespace.forEach { char ->
        when {
            char.isWhitespace() -> pendingSpace = isNotEmpty()
            char.isISOControl() -> Unit
            else -> {
                if (pendingSpace) append(' ')
                pendingSpace = false
                append(char)
            }
        }
    }
}

/** [String.take] on code points, so a cap can never cut a surrogate pair in half. */
internal fun String.takeCodePoints(max: Int): String {
    if (codePointCount(0, length) <= max) return this
    return substring(0, offsetByCodePoints(0, max))
}

/**
 * A stored report plus the filesystem-derived state the UI needs.
 *
 * [isOngoingRecording] and [recordingLogSize] are never persisted — they are derived in
 * [BugReportRepo.scan] from the live [BugReportRecorder] state + the `.recording` sentinel.
 * [logSizeBytes] is the on-disk size of the report's `report.log`, shown in the detail view.
 */
data class BugReportInfo(
    val report: BugReport,
    val isSeen: Boolean,
    val isOngoingRecording: Boolean = false,
    val recordingLogSize: Long = 0L,
    val logSizeBytes: Long = 0L,
) {
    val id: String get() = report.id
}
