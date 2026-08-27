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

data class MoveOperationReport(
    override val affectedPaths: Collection<PathChange>,
    override val subjectPath: APath<*>?,
    val skipped: Collection<APathLookup<*>>,
    val movedFiles: Int,
    val movedDirectories: Int,
    val bytesMoved: Long,
    override val performanceHistory: PerformanceHistory?,
) : ExplorerOperation.Report {

    override val summary: CaString = caString {
        buildString {
            if (movedFiles > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_files_moved, movedFiles)
                )
                append(" ")
            }
            if (movedDirectories > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_directories_moved, movedDirectories)
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
            if (bytesMoved > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.explorer_operation_report_bytes_moved,
                        bytesMoved.toInt(),
                        formatFileSize(it, bytesMoved)
                    )
                )
            }
        }
    }

    override fun toString(): String {
        return "MoveOperationReport(affectedPaths=${affectedPaths.size}, skipped=${skipped.size}, movedFiles=$movedFiles, movedDirectories=$movedDirectories, bytesMoved=$bytesMoved, performanceHistory=${performanceHistory?.samples?.size} samples)"
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private val skipped = mutableListOf<APathLookup<*>>()
        private var movedFiles: Int = 0
        private var movedDirectories: Int = 0
        private var bytesMoved: Long = 0
        private var performanceHistory: PerformanceHistory? = null
        private var subjectPath: APath<*>? = null

        fun addMovedItems(sources: Collection<Pair<APathLookup<*>, APathLookup<*>>>) {
            val affected = sources.map { (source, destLookup) ->
                if (destLookup.isDirectory) movedDirectories++ else movedFiles++
                PathChange(
                    path = destLookup.lookedUp,
                    change = PathChange.Change.MOVED,
                    previousPath = source.lookedUp,
                )
            }
            affectedPaths.addAll(affected)
        }

        fun setSkipped(items: Set<APathLookup<*>>) {
            skipped.addAll(items)
        }

        fun setBytesMoved(bytes: Long) {
            this.bytesMoved = bytes
        }

        fun setPerformanceHistory(history: PerformanceHistory?) {
            this.performanceHistory = history
        }

        /** The top-level destination, not whichever descendant the engine moved first. */
        fun setSubjectPath(path: APath<*>?) {
            this.subjectPath = path
        }

        fun build(): MoveOperationReport = MoveOperationReport(
            affectedPaths = affectedPaths.distinct(),
            subjectPath = subjectPath,
            skipped = skipped,
            movedFiles = movedFiles,
            movedDirectories = movedDirectories,
            bytesMoved = bytesMoved,
            performanceHistory = performanceHistory,
        )
    }
}
