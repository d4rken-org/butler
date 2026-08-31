package eu.darken.butler.common.debug.bugreport

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The resolved roots of the bug-report store, shared by [BugReportRepo] and [BugReportRecorder]:
 * external app-specific storage first (new reports go there, because the root and ADB helpers append
 * their own logs into the report directory and cannot write into `filesDir`), the private `filesDir`
 * second (reports from older versions, and the write root while no external volume is mounted).
 *
 * Resolved once here so every consumer agrees on the same roots. Accepted limitation: an external
 * volume that only becomes available mid-process is not picked up until the next start.
 */
@Singleton
class BugReportStorageLayout @Inject constructor(
    @ApplicationContext context: Context,
) {

    /** Read order: the first root holding a report wins. */
    val roots: List<File> = listOfNotNull(
        context.getExternalFilesDir(null)?.let { File(it, BugReportStorage.REPORTS_DIRNAME) },
        BugReportStorage.reportsDir(context),
    )

    /** Where new reports are written. */
    val writeRoot: File = roots.first()

    fun findReportDir(id: String): File? = roots.map { File(it, id) }.firstOrNull { it.isDirectory }

    /** Every physical copy of [id], so a delete cannot leave a shadowed one behind. */
    fun allReportDirs(id: String): List<File> = roots.map { File(it, id) }.filter { it.isDirectory }
}
