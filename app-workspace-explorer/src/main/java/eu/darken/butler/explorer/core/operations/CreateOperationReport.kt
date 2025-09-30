package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.operations.Operation.Report.*

data class CreateOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val createdFiles: Int,
    val createdDirectories: Int,
) : ExplorerOperation.Report {

    override val summary: CaString = caString {
        buildString {
            if (createdFiles > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_files_created, createdFiles)
                )
                append(" ")
            }
            if (createdDirectories > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_directories_created, createdDirectories)
                )
            }
        }
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private var createdFiles: Int = 0
        private var createdDirectories: Int = 0

        fun addPathEvent(event: FileSystemEvent) {
            affectedPaths.addAll(
                when (event) {
                    is FileSystemEvent.Added -> event.paths.map {
                        if (it.isDirectory) createdDirectories++ else createdFiles++
                        PathChange(it.lookedUp, PathChange.Change.ADDED)
                    }
                    is FileSystemEvent.Modified -> event.paths.map {
                        PathChange(it.lookedUp, PathChange.Change.MODIFIED)
                    }
                    is FileSystemEvent.Removed -> event.paths.map {
                        PathChange(it.lookedUp, PathChange.Change.REMOVED)
                    }
                }
            )
        }

        fun build(): CreateOperationReport = CreateOperationReport(
            affectedPaths = affectedPaths.distinct(),
            createdFiles = createdFiles,
            createdDirectories = createdDirectories,
        )
    }
}