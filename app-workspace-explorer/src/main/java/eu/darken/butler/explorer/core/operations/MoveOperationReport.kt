package eu.darken.butler.explorer.core.operations

import android.text.format.Formatter.*
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.operations.Operation.Report.*

data class MoveOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val skipped: Collection<APath>,
    val movedFiles: Int,
    val movedDirectories: Int,
    val bytesMoved: Long,
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
                    it.getQuantityString2(R.plurals.explorer_operation_report_skipped_items, skipped.size)
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
        return "MoveOperationReport(affectedPaths=${affectedPaths.size}, skipped=${skipped.size}, movedFiles=$movedFiles, movedDirectories=$movedDirectories, bytesMoved=$bytesMoved)"
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private val skipped = mutableListOf<APath>()
        private var movedFiles: Int = 0
        private var movedDirectories: Int = 0
        private var bytesMoved: Long = 0

        fun addMovedItems(sources: Collection<Pair<APath, APathLookup<*>>>) {
            val affected = sources.flatMap { (source, destLookup) ->
                if (destLookup.isDirectory) movedDirectories++ else movedFiles++
                listOf(
                    PathChange(source, PathChange.Change.REMOVED),
                    PathChange(destLookup.lookedUp, PathChange.Change.ADDED),
                )
            }
            affectedPaths.addAll(affected)
        }

        fun setSkipped(items: Set<APath>) {
            skipped.addAll(items)
        }

        fun setBytesMoved(bytes: Long) {
            this.bytesMoved = bytes
        }

        fun build(): MoveOperationReport = MoveOperationReport(
            affectedPaths = affectedPaths.distinct(),
            skipped = skipped,
            movedFiles = movedFiles,
            movedDirectories = movedDirectories,
            bytesMoved = bytesMoved,
        )
    }
}
