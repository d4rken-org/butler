package eu.darken.butler.viewer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.workspace.core.operations.BaseDeleteOperationReport
import eu.darken.butler.workspace.core.operations.Operation.Report.PathChange

data class DeleteOperationReport(
    override val affectedPaths: Collection<PathChange>,
    override val skipped: Collection<APathLookup<*>>,
    override val trashedFiles: Int,
    override val trashedDirectories: Int,
    override val deletedFiles: Int,
    override val deletedDirectories: Int,
    override val bytesFreed: Long,
    override val performanceHistory: PerformanceHistory?,
    override val subjectPath: APath<*>?,
) : BaseDeleteOperationReport(
    affectedPaths = affectedPaths,
    skipped = skipped,
    trashedFiles = trashedFiles,
    trashedDirectories = trashedDirectories,
    deletedFiles = deletedFiles,
    deletedDirectories = deletedDirectories,
    bytesFreed = bytesFreed,
    performanceHistory = performanceHistory,
    subjectPath = subjectPath,
), ViewerOperation.Report {

    override fun toString(): String {
        return "DeleteOperationReport(affectedPaths=${affectedPaths.size}, skipped=${skipped.size}, " +
            "trashedFiles=$trashedFiles, deletedFiles=$deletedFiles, bytesFreed=$bytesFreed)"
    }

    class Builder : BaseDeleteOperationReport.Builder<DeleteOperationReport>() {
        override fun build(): DeleteOperationReport = DeleteOperationReport(
            affectedPaths = affectedPaths.toList(),
            skipped = skipped,
            trashedFiles = trashedFiles,
            trashedDirectories = trashedDirectories,
            deletedFiles = deletedFiles,
            deletedDirectories = deletedDirectories,
            bytesFreed = _bytesFreed,
            performanceHistory = _performanceHistory,
            subjectPath = _subjectPath,
        )
    }
}
