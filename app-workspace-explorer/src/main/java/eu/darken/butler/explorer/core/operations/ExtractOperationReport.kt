package eu.darken.butler.explorer.core.operations

import android.text.format.Formatter.formatFileSize
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.operations.Operation.Report.PathChange

data class ExtractOperationReport(
    override val affectedPaths: Collection<PathChange>,
    override val subjectPath: APath<*>,
    val extractedFiles: Int,
    val skippedEntries: Collection<String>,
    val extractedBytes: Long,
    override val performanceHistory: PerformanceHistory?,
) : ExplorerOperation.Report {

    override val partialErrorCount: Int get() = skippedEntries.size

    override val summary: CaString = caString {
        buildString {
            if (extractedFiles > 0) {
                append(it.getQuantityString2(R.plurals.explorer_operation_report_files_extracted, extractedFiles))
                append(" ")
            }
            if (skippedEntries.isNotEmpty()) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_skipped_items,
                        skippedEntries.size,
                    )
                )
                append(" ")
            }
            if (extractedBytes > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.explorer_operation_report_bytes_extracted,
                        extractedBytes.toInt(),
                        formatFileSize(it, extractedBytes),
                    )
                )
            }
        }
    }

    /** [archive] is required: an extraction is about the archive, never about an entry inside it. */
    class Builder(private val archive: APath<*>) {
        private val affectedPaths = mutableListOf<PathChange>()
        private val skipped = mutableListOf<String>()
        private var extractedFiles = 0
        private var extractedBytes = 0L
        private var performanceHistory: PerformanceHistory? = null

        fun addExtracted(path: APath<*>, bytes: Long) {
            affectedPaths.add(PathChange(path, PathChange.Change.ADDED))
            extractedFiles++
            extractedBytes += bytes
        }

        fun addSkipped(entry: String) {
            skipped.add(entry)
        }

        fun setPerformanceHistory(history: PerformanceHistory?) {
            performanceHistory = history
        }

        fun build(): ExtractOperationReport = ExtractOperationReport(
            affectedPaths = affectedPaths.distinct(),
            subjectPath = archive,
            extractedFiles = extractedFiles,
            skippedEntries = skipped,
            extractedBytes = extractedBytes,
            performanceHistory = performanceHistory,
        )
    }
}
