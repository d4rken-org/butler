package eu.darken.butler.explorer.core.operations

import android.text.format.Formatter.*
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.operations.Operation.Report.*

data class CopyOperationReport(
    override val affectedPaths: Collection<PathChange>,
    override val subjectPath: APath<*>?,
    val skipped: Collection<APathLookup<*>>,
    val copiedFiles: Int,
    val copiedDirectories: Int,
    val copiedBytes: Long,
    override val performanceHistory: PerformanceHistory?,
) : ExplorerOperation.Report {

    override val summary: CaString = caString {
        buildString {
            if (copiedFiles > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_files_copied, copiedFiles)
                )
                append(" ")
            }
            if (copiedDirectories > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_directories_copied, copiedDirectories)
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
            if (copiedBytes > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.explorer_operation_report_bytes_copied,
                        copiedBytes.toInt(),
                        formatFileSize(it, copiedBytes)
                    )
                )
            }
        }
    }

    override fun toString(): String {
        return "CopyOperationReport(affectedPaths=${affectedPaths.size}, skipped=${skipped.size}, copiedFiles=$copiedFiles, copiedDirectories=$copiedDirectories, copiedBytes=$copiedBytes, performanceHistory=${performanceHistory?.samples?.size} samples)"
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private val skipped = mutableListOf<APathLookup<*>>()
        private var copiedFiles: Int = 0
        private var copiedDirectories: Int = 0
        private var copiedBytes: Long = 0
        private var performanceHistory: PerformanceHistory? = null
        private var subjectPath: APath<*>? = null

        fun addCopiedItems(lookups: Collection<APathLookup<*>>) {
            val affected = lookups.map { lookup ->
                if (lookup.isDirectory) copiedDirectories++ else copiedFiles++
                PathChange(lookup.lookedUp, PathChange.Change.ADDED)
            }
            affectedPaths.addAll(affected)
        }

        fun setSkipped(items: Set<APathLookup<*>>) {
            skipped.addAll(items)
        }

        fun setCopiedBytes(bytes: Long) {
            this.copiedBytes = bytes
        }

        fun setPerformanceHistory(history: PerformanceHistory?) {
            this.performanceHistory = history
        }

        /** The top-level destination, not whichever descendant the engine copied first. */
        fun setSubjectPath(path: APath<*>?) {
            this.subjectPath = path
        }

        fun build(): CopyOperationReport = CopyOperationReport(
            affectedPaths = affectedPaths.distinct(),
            subjectPath = subjectPath,
            skipped = skipped,
            copiedFiles = copiedFiles,
            copiedDirectories = copiedDirectories,
            copiedBytes = copiedBytes,
            performanceHistory = performanceHistory,
        )
    }
}