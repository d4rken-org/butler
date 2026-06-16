package eu.darken.butler.common.debug.bugreport

import eu.darken.butler.common.serialization.InstantSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A locally-stored bug report: metadata + the error that triggered it. The accompanying log trail
 * is stored next to it on disk (`report.log`) rather than inline, so it can be large.
 *
 * All metadata fields are plain strings so collection can degrade to `"unavailable: …"` without
 * aborting the report (see [BugReportRepo]).
 */
@Serializable
data class BugReport(
    val id: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val type: Type,
    val errorClass: String,
    val errorMessage: String,
    val stackTrace: String,
    val threadName: String,
    val appVersion: String,
    val deviceFingerprint: String,
    val apiLevel: String,
    val flavor: String,
    val buildType: String,
    val installId: String,
    val locale: String,
) {
    enum class Type { CRASH, REPORTED }
}

/** A stored report plus the filesystem-derived state the UI needs (whether the user acknowledged it). */
data class BugReportInfo(
    val report: BugReport,
    val isSeen: Boolean,
) {
    val id: String get() = report.id
}
