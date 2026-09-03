package eu.darken.butler.common.debug.bugreport

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.text.Normalizer

/**
 * Shared on-disk layout for the unified bug-report store, used by both [BugReportRepo] (crashes +
 * manual snapshots) and [BugReportRecorder] (ongoing recordings) so the two never drift.
 *
 * ```
 * <root>/bugreports/<id>/   meta.json | report.log | root.log? | adb.log? | .seen? | .recording (while live)
 * <root>/bugreports/.tmp-<id>/   snapshot two-phase writes
 * cacheDir/bugreports_share/[<label>_]<id>.zip
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

    /**
     * Present only while a [BugReport.Type.RECORDING] report is actively being written. Doubles as
     * the resume marker: if it survives a process death, the next main-process launch reattaches to
     * the recording ([BugReportRecorder.recoverInterruptedRecording]).
     */
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

    /**
     * File name of a report's share zip: the label makes it recognizable in a mail client, the id
     * keeps it unique and lets [isShareZipFor] find it again.
     */
    fun shareZipName(id: String, label: String?): String {
        val sanitized = label?.let { sanitizeForFileName(it) }?.takeIf { it.isNotEmpty() }
        return if (sanitized == null) "$id.zip" else "${sanitized}_$id.zip"
    }

    /**
     * Whether [fileName] is a share zip of [id], under any label it has ever carried, including the
     * [TMP_SUFFIX] form a crashed zip build leaves behind. Ids have a fixed `type_millis_uuid8`
     * shape, so no id is a suffix of another one.
     */
    fun isShareZipFor(fileName: String, id: String): Boolean {
        val name = fileName.removeSuffix(TMP_SUFFIX)
        return name == "$id.zip" || name.endsWith("_$id.zip")
    }

    /**
     * Unicode letters and digits are kept — an ASCII-only filter would sanitize `厨房` to nothing and
     * `Küche` to `K_che`, which is exactly the recognizability the label exists for. Only what a file
     * system reads as structure is replaced.
     */
    private fun sanitizeForFileName(label: String): String = Normalizer
        .normalize(label, Normalizer.Form.NFC)
        .map { if (it in RESERVED_NAME_CHARS || it.isISOControl()) '_' else it }
        .joinToString("")
        .replace(UNDERSCORE_RUN, "_")
        // A leading dot would make the zip a hidden file.
        .trimStart('.')
        .trim('_')
        .takeCodePoints(MAX_ZIP_LABEL_LENGTH)

    private val RESERVED_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private val UNDERSCORE_RUN = Regex("_+")

    /** The label is a hint in a file listing, not the whole name — the id still has to fit next to it. */
    private const val MAX_ZIP_LABEL_LENGTH = 48
}
