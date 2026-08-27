package eu.darken.butler.explorer.core.operations

import android.text.format.Formatter.formatFileSize
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.operations.Operation.Report.PathChange

data class CompressOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val compressedFiles: Int,
    val outputArchive: APath<*>,
    val outputBytes: Long,
    override val performanceHistory: PerformanceHistory?,
) : ExplorerOperation.Report {

    override val subjectPath: APath<*> get() = outputArchive

    override val summary: CaString = caString {
        buildString {
            append(it.getQuantityString2(R.plurals.explorer_operation_report_files_compressed, compressedFiles))
            if (outputBytes > 0) {
                append(" (")
                append(formatFileSize(it, outputBytes))
                append(")")
            }
        }
    }

    class Builder(private val outputArchive: APath<*>) {
        private var compressedFiles = 0
        private var outputBytes = 0L
        private var performanceHistory: PerformanceHistory? = null

        fun addCompressedFile() {
            compressedFiles++
        }

        fun setOutputBytes(bytes: Long) {
            outputBytes = bytes
        }

        fun setPerformanceHistory(history: PerformanceHistory?) {
            performanceHistory = history
        }

        fun build(): CompressOperationReport = CompressOperationReport(
            affectedPaths = listOf(PathChange(outputArchive, PathChange.Change.ADDED)),
            compressedFiles = compressedFiles,
            outputArchive = outputArchive,
            outputBytes = outputBytes,
            performanceHistory = performanceHistory,
        )
    }
}
