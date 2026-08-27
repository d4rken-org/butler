package eu.darken.butler.workspace.core.operations

import android.text.format.Formatter
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation.Report.PathChange

abstract class BaseDeleteOperationReport(
    override val affectedPaths: Collection<PathChange>,
    open val skipped: Collection<APathLookup<*>>,
    open val trashedFiles: Int,
    open val trashedDirectories: Int,
    open val deletedFiles: Int,
    open val deletedDirectories: Int,
    open val bytesFreed: Long,
    override val performanceHistory: PerformanceHistory?,
    override val subjectPath: APath<*>?,
) : Operation.Report, Operation.HasPerformanceHistory {

    override val summary: CaString = caString {
        buildString {
            if (trashedFiles > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.workspace_operation_report_files_trashed,
                        trashedFiles
                    )
                )
                append(" ")
            }
            if (trashedDirectories > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.workspace_operation_report_directories_trashed,
                        trashedDirectories
                    )
                )
                append(" ")
            }
            if (deletedFiles > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.workspace_operation_report_files_deleted,
                        deletedFiles
                    )
                )
                append(" ")
            }
            if (deletedDirectories > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.workspace_operation_report_directories_deleted,
                        deletedDirectories
                    )
                )
                append(" ")
            }
            if (skipped.isNotEmpty()) {
                append(
                    it.getQuantityString2(
                        R.plurals.workspace_operation_report_skipped_items,
                        skipped.size
                    )
                )
                append(" ")
            }
            if (bytesFreed > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.workspace_operation_report_bytes_freed,
                        if (bytesFreed > 1) 2 else 1,
                        Formatter.formatFileSize(it, bytesFreed)
                    )
                )
            }
        }
    }

    abstract class Builder<T : BaseDeleteOperationReport> {
        protected val affectedPaths = mutableSetOf<PathChange>()
        protected val skipped = mutableListOf<APathLookup<*>>()
        protected var trashedFiles: Int = 0
        protected var trashedDirectories: Int = 0
        protected var deletedFiles: Int = 0
        protected var deletedDirectories: Int = 0
        protected var _bytesFreed: Long = 0
        protected var _performanceHistory: PerformanceHistory? = null
        protected var _subjectPath: APath<*>? = null

        fun setTrashed(items: Set<APathLookup<*>>) {
            items.forEach {
                if (it.isDirectory) trashedDirectories++ else trashedFiles++
                affectedPaths.add(PathChange(it.lookedUp, PathChange.Change.TRASHED))
            }
        }

        fun setDeletions(items: Set<APathLookup<*>>) {
            items.forEach {
                if (it.isDirectory) deletedDirectories++ else deletedFiles++
                affectedPaths.add(PathChange(it.lookedUp, PathChange.Change.REMOVED))
            }
        }

        fun setSkipped(items: Set<APathLookup<*>>) {
            skipped.addAll(items)
        }

        fun setBytesFreed(bytesFreed: Long) {
            this._bytesFreed = bytesFreed
        }

        fun setPerformanceHistory(history: PerformanceHistory?) {
            this._performanceHistory = history
        }

        /** The target the user selected, not whichever descendant the engine reached first. */
        fun setSubjectPath(path: APath<*>?) {
            this._subjectPath = path
        }

        abstract fun build(): T
    }
}
