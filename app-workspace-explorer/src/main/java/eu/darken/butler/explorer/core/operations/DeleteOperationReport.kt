package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.ByteFormatter
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.operations.Operation.Report.*

data class DeleteOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val deletedFiles: Int,
    val deletedDirectories: Int,
    val bytesFreed: Long,
) : ExplorerOperation.Report {

    override val summary: CaString = caString {
        buildString {
            if (deletedFiles > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_files_deleted, deletedFiles)
                )
                append(" ")
            }
            if (deletedDirectories > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_directories_deleted, deletedDirectories)
                )
                append(" ")
            }
            if (bytesFreed > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.explorer_operation_report_bytes_freed,
                        bytesFreed.toInt(),
                        ByteFormatter.formatFileSize(it, bytesFreed)
                    )
                )
            }
        }
    }

    class Builder() {
        private val affectedPaths = mutableListOf<PathChange>()
        private var deletedFiles: Int = 0
        private var deletedDirectories: Int = 0

        fun addPathEvent(event: FileSystemEvent) {
            affectedPaths.addAll(
                when (event) {
                    is FileSystemEvent.Added -> event.paths.map {
                        if (it.isDirectory) deletedDirectories++ else deletedFiles++
                        PathChange(it.lookedUp, PathChange.Change.ADDED)
                    }
                    is FileSystemEvent.Modified -> event.paths.map {
                        if (it.isDirectory) deletedDirectories++ else deletedFiles++
                        PathChange(it.lookedUp, PathChange.Change.MODIFIED)
                    }
                    is FileSystemEvent.Removed -> event.paths.map {
                        if (it.isDirectory) deletedDirectories++ else deletedFiles++
                        PathChange(it.lookedUp, PathChange.Change.REMOVED)
                    }
                }
            )
        }

        private var bytesFreed: Long = 0

        fun setBytesFreed(bytesFreed: Long) {
            this.bytesFreed = bytesFreed
        }

        fun build(): DeleteOperationReport = DeleteOperationReport(
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