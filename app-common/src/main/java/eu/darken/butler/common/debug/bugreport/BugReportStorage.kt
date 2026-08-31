package eu.darken.butler.common.debug.bugreport

import android.content.Context
import java.io.File

/**
 * Shared on-disk layout for the unified bug-report store, used by both [BugReportRepo] (crashes +
 * manual snapshots) and [BugReportRecorder] (ongoing recordings) so the two never drift.
 *
 * ```
 * <root>/bugreports/<id>/   meta.json | report.log | .seen? | .recording (while live)
 * <root>/bugreports/.tmp-<id>/   snapshot two-phase writes
 * cacheDir/bugreports_share/<id>.zip
 * ```
 *
 * `<root>` is resolved by [BugReportStorageLayout], which owns the order of the two report roots.
 */
internal object BugReportStorage {
    const val REPORTS_DIRNAME = "bugreports"
    const val SHARE_DIRNAME = "bugreports_share"
    const val TMP_PREFIX = ".tmp-"
    const val META_FILE = "meta.json"
    const val LOG_FILE = "report.log"
    const val SEEN_MARKER = ".seen"

    /** Present only while a [BugReport.Type.RECORDING] report is actively being written. */
    const val RECORDING_SENTINEL = ".recording"

    const val RECORDING_ID_PREFIX = "recording_"

    fun reportsDir(context: Context): File = File(context.filesDir, REPORTS_DIRNAME)

    fun shareDir(context: Context): File = File(context.cacheDir, SHARE_DIRNAME)
}
