package eu.darken.butler.searcher.core.operations

import android.text.format.Formatter
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.workspace.core.operations.Operation.Report.*

data class DeleteOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val skipped: Collection<APathLookup<*>>,
    val trashedFiles: Int,
    val trashedDirectories: Int,
    val deletedFiles: Int,
    val deletedDirectories: Int,
    val bytesFreed: Long,
    override val performanceHistory: PerformanceHistory?,
) : SearcherOperation.Report {

    override val summary: CaString = caString {
        buildString {
            if (trashedFiles > 0) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_files_trashed,
                        trashedFiles
                    )
                )
                append(" ")
            }
            if (trashedDirectories > 0) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_directories_trashed,
                        trashedDirectories
                    )
                )
                append(" ")
            }
            if (deletedFiles > 0) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_files_deleted,
                        deletedFiles
                    )
                )
                append(" ")
            }
            if (deletedDirectories > 0) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_directories_deleted,
                        deletedDirectories
                    )
                )
                append(" ")
            }
            if (skipped.isNotEmpty()) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_skipped_items,
                        skipped.size
                    )
                )
                append(" ")
            }
            if (bytesFreed > 0) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_bytes_freed,
                        bytesFreed.toInt(),
                        Formatter.formatFileSize(it, bytesFreed)
                    )
                )
            }
        }
    }

    override fun toString(): String {
        return "DeleteOperationReport(affectedPaths=${affectedPaths.size}, skipped=${skipped.size}, trashedFiles=$trashedFiles, trashedDirectories=$trashedDirectories, deletedFiles=$deletedFiles, deletedDirectories=$deletedDirectories, bytesFreed=$bytesFreed, performanceHistory=${performanceHistory?.samples?.size} samples)"
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private val skipped = mutableListOf<APathLookup<*>>()
        private var trashedFiles: Int = 0
        private var trashedDirectories: Int = 0
        private var deletedFiles: Int = 0
        private var deletedDirectories: Int = 0
        private var performanceHistory: PerformanceHistory? = null

        fun setTrashed(items: Set<APathLookup<*>>) {
            val affected = items.map {
                if (it.isDirectory) trashedDirectories++ else trashedFiles++
                PathChange(it.lookedUp, PathChange.Change.TRASHED)
            }
            affectedPaths.addAll(affected)
        }

        fun setDeletions(items: Set<APathLookup<*>>) {
            val affected = items.map {
                if (it.isDirectory) deletedDirectories++ else deletedFiles++
                PathChange(it.lookedUp, PathChange.Change.REMOVED)
            }
            affectedPaths.addAll(affected)
        }

        fun setSkipped(items: Set<APathLookup<*>>) {
            skipped.addAll(items)
        }

        private var bytesFreed: Long = 0

        fun setBytesFreed(bytesFreed: Long) {
            this.bytesFreed = bytesFreed
        }

        fun setPerformanceHistory(history: PerformanceHistory?) {
            this.performanceHistory = history
        }

        fun build(): DeleteOperationReport = DeleteOperationReport(
            affectedPaths = affectedPaths.distinct(),
            skipped = skipped,
            trashedFiles = trashedFiles,
            trashedDirectories = trashedDirectories,
            deletedFiles = deletedFiles,
            deletedDirectories = deletedDirectories,
            bytesFreed = bytesFreed,
            performanceHistory = performanceHistory,
        )
    }
}
