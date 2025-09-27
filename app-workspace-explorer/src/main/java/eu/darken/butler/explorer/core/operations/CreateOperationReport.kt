package eu.darken.butler.explorer.core.operations

import android.text.format.Formatter.*
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.operations.Operation.Report.*
import kotlin.time.Clock
import kotlin.time.Instant

data class CreateOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val deletedFiles: Int,
    val deletedDirectories: Int,
    val bytesFreed: Long,
) : ExplorerOperation.Report {

    override val summary: CaString = caString {
        buildString {
            append(
                it.getQuantityString2(R.plurals.explorer_operation_report_files_deleted, deletedFiles)
            )
            append(" ")
            append(
                it.getQuantityString2(R.plurals.explorer_operation_report_directories_deleted, deletedDirectories)
            )
            append(" ")
            append(
                it.getQuantityString2(
                    R.plurals.explorer_operation_report_bytes_freed,
                    bytesFreed.toInt(),
                    formatFileSize(it, bytesFreed)
                )
            )
        }
    }

    class Builder(
        private val startTime: Instant = Clock.System.now()
    ) {
        private val affectedPaths = mutableListOf<PathChange>()
        private var deletedFiles: Int = 0
        private var deletedDirectories: Int = 0

        fun addPathEvent(event: FileSystemEvent) {
            affectedPaths.addAll(
                when (event) {
                    is FileSystemEvent.Added -> event.paths.map { PathChange(it.lookedUp, PathChange.Change.ADDED) }
                    is FileSystemEvent.Modified -> event.paths.map {
                        PathChange(
                            it.lookedUp,
                            PathChange.Change.MODIFIED
                        )
                    }
                    is FileSystemEvent.Removed -> event.paths.map { PathChange(it.lookedUp, PathChange.Change.REMOVED) }
                }
            )
        }

        private var bytesFreed: Long = 0

        fun setBytesFreed(bytesFreed: Long) {
            this.bytesFreed = bytesFreed
        }

        fun build(): CreateOperationReport = CreateOperationReport(
            affectedPaths = affectedPaths.distinct(),
            deletedFiles = deletedFiles,
            deletedDirectories = deletedDirectories,
            bytesFreed = bytesFreed,
        )
    }

    data class SpeedInfo(
        val current: Long = 0,
        val average: Long = 0,
        val peak: Long = 0,
    )
}