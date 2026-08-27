package eu.darken.butler.developer.core.operations

import android.text.format.Formatter
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.developer.R
import eu.darken.butler.workspace.core.operations.Operation.Report.PathChange

data class TestDataOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val filesCreated: Int,
    val directoriesCreated: Int,
    val totalSize: Long,
) : DeveloperOperation.Report {

    /**
     * The generators add inner files before the directory the user named, and test data never
     * enters history (its metadata kind is null), so there is no honest subject to name.
     */
    override val subjectPath: APath<*>? = null

    override val summary: CaString = caString { context ->
        buildString {
            if (filesCreated > 0) {
                append(context.getQuantityString2(R.plurals.developer_testdata_report_files_created, filesCreated))
            }
            if (directoriesCreated > 0) {
                if (isNotEmpty()) append(", ")
                append(context.getQuantityString2(R.plurals.developer_testdata_report_dirs_created, directoriesCreated))
            }
            if (totalSize > 0) {
                append(" (")
                append(Formatter.formatShortFileSize(context, totalSize))
                append(")")
            }
        }
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private var filesCreated = 0
        private var directoriesCreated = 0
        private var totalSize = 0L

        fun addFile(path: APath<*>, size: Long) {
            affectedPaths.add(PathChange(path, PathChange.Change.ADDED))
            filesCreated++
            totalSize += size
        }

        fun addDirectory(path: APath<*>) {
            affectedPaths.add(PathChange(path, PathChange.Change.ADDED))
            directoriesCreated++
        }

        fun build() = TestDataOperationReport(
            affectedPaths = affectedPaths,
            filesCreated = filesCreated,
            directoriesCreated = directoriesCreated,
            totalSize = totalSize,
        )
    }
}
