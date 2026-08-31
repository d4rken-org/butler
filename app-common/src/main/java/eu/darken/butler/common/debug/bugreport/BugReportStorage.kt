package eu.darken.butler.common.debug.bugreport

import android.content.Context
import java.io.File
import java.nio.file.Files

/**
 * Shared on-disk layout for the unified bug-report store, used by both [BugReportRepo] (crashes +
 * manual snapshots) and [BugReportRecorder] (ongoing recordings) so the two never drift.
 *
 * ```
 * <root>/bugreports/<id>/   meta.json | report.log | root.log? | adb.log? | .seen? | .recording (while live)
 * <root>/bugreports/.tmp-<id>/   snapshot two-phase writes
 * cacheDir/bugreports_share/<id>.zip
 * ```
 *
 * `<root>` is resolved by [BugReportStorageLayout], which owns the order of the two report roots.
 */
object BugReportStorage {
    const val REPORTS_DIRNAME = "bugreports"
    const val SHARE_DIRNAME = "bugreports_share"
    const val TMP_PREFIX = ".tmp-"

    /** Suffix of a share zip that is still being written. */
    const val TMP_SUFFIX = ".tmp"
    const val META_FILE = "meta.json"
    const val LOG_FILE = "report.log"

    /** Written by the root helper process while a recording is active. */
    const val ROOT_LOG_FILE = "root.log"

    /** Written by the Shizuku/ADB helper process while a recording is active. */
    const val ADB_LOG_FILE = "adb.log"

    const val SEEN_MARKER = ".seen"

    /** Present only while a [BugReport.Type.RECORDING] report is actively being written. */
    const val RECORDING_SENTINEL = ".recording"

    const val RECORDING_ID_PREFIX = "recording_"

    fun reportsDir(context: Context): File = File(context.filesDir, REPORTS_DIRNAME)

    fun shareDir(context: Context): File = File(context.cacheDir, SHARE_DIRNAME)

    /**
     * The files that may leave the device in a share zip, in a fixed order. An allowlist rather than
     * a directory listing: the helper processes write into the report directory, so anything not
     * named here — including a symlink pointing somewhere else entirely — is never packaged.
     */
    fun payloadFiles(reportDir: File): List<File> = listOf(META_FILE, LOG_FILE, ROOT_LOG_FILE, ADB_LOG_FILE)
        .map { File(reportDir, it) }
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
}
