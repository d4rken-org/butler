package eu.darken.butler.searcher.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.searcher.R
import eu.darken.butler.workspace.core.operations.Operation.Report.Paths.PathChange

data class RenameOperationReport(
    override val affectedPaths: Collection<PathChange>,
    override val subjectPath: APath<*>?,
    val skipped: Collection<APathLookup<*>>,
    val movedFiles: Int,
    val movedDirectories: Int,
    val bytesMoved: Long,
    override val performanceHistory: PerformanceHistory?,
) : SearcherOperation.Report {

    override val summary: CaString = caString {
        buildString {
            subjectPath?.let { subject ->
                append(it.getString(R.string.searcher_operation_report_renamed, subject.name))
                append(" ")
            }
            if (skipped.isNotEmpty()) {
                append(
                    it.getQuantityString2(
                        eu.darken.butler.workspace.R.plurals.workspace_operation_report_skipped_items,
                        skipped.size
                    )
                )
            }
        }.trim()
    }

    override fun toString(): String {
        return "RenameOperationReport(affectedPaths=${affectedPaths.size}, subjectPath=$subjectPath, skipped=${skipped.size}, movedFiles=$movedFiles, movedDirectories=$movedDirectories, bytesMoved=$bytesMoved, performanceHistory=${performanceHistory?.samples?.size} samples)"
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private val skipped = mutableListOf<APathLookup<*>>()
        private var movedFiles: Int = 0
        private var movedDirectories: Int = 0
        private var bytesMoved: Long = 0
        private var performanceHistory: PerformanceHistory? = null
        private var subjectPath: APath<*>? = null

        fun addMovedItems(items: Collection<Pair<APathLookup<*>, APathLookup<*>>>) {
            val affected = items.map { (source, destLookup) ->
                if (destLookup.isDirectory) movedDirectories++ else movedFiles++
                PathChange(
                    path = destLookup.lookedUp,
                    change = PathChange.Change.MOVED,
                    previousPath = source.lookedUp,
                )
            }
            affectedPaths.addAll(affected)
        }

        fun setSkipped(items: Collection<APathLookup<*>>) {
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

        fun build(): RenameOperationReport = RenameOperationReport(
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
