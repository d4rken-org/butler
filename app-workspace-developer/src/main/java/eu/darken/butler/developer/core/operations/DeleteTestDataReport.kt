package eu.darken.butler.developer.core.operations

import android.text.format.Formatter
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.developer.R
import eu.darken.butler.workspace.core.operations.Operation.Report.PathChange

data class DeleteTestDataReport(
    override val affectedPaths: Collection<PathChange>,
    val filesDeleted: Int,
    val directoriesDeleted: Int,
    val freedSize: Long,
) : DeveloperOperation.Report {

    override val summary: CaString = caString { context ->
        buildString {
            if (filesDeleted > 0) {
                append(context.getQuantityString2(R.plurals.developer_testdata_report_files_deleted, filesDeleted))
            }
            if (directoriesDeleted > 0) {
                if (isNotEmpty()) append(", ")
                append(context.getQuantityString2(R.plurals.developer_testdata_report_dirs_deleted, directoriesDeleted))
            }
            if (freedSize > 0) {
                append(" (")
                append(Formatter.formatShortFileSize(context, freedSize))
                append(")")
            }
        }
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private var filesDeleted = 0
        private var directoriesDeleted = 0
        private var freedSize = 0L

        fun addDeletedFile(path: APath<*>, size: Long) {
            affectedPaths.add(PathChange(path, PathChange.Change.REMOVED))
            filesDeleted++
            freedSize += size
        }

        fun addDeletedDirectory(path: APath<*>) {
            affectedPaths.add(PathChange(path, PathChange.Change.REMOVED))
            directoriesDeleted++
        }

        fun build() = DeleteTestDataReport(
            affectedPaths = affectedPaths,
            filesDeleted = filesDeleted,
            directoriesDeleted = directoriesDeleted,
            freedSize = freedSize,
        )
    }
}
