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

data class CopyOperationReport(
    override val affectedPaths: Collection<PathChange>,
    val skipped: Collection<APath>,
    val copiedFiles: Int,
    val copiedDirectories: Int,
    val bytesCopied: Long,
) : ExplorerOperation.Report {

    override val summary: CaString = caString {
        buildString {
            if (copiedFiles > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_files_copied, copiedFiles)
                )
                append(" ")
            }
            if (copiedDirectories > 0) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_directories_copied, copiedDirectories)
                )
                append(" ")
            }
            if (skipped.isNotEmpty()) {
                append(
                    it.getQuantityString2(R.plurals.explorer_operation_report_skipped_items, skipped.size)
                )
                append(" ")
            }
            if (bytesCopied > 0) {
                append(
                    it.getQuantityString2(
                        R.plurals.explorer_operation_report_bytes_copied,
                        bytesCopied.toInt(),
                        formatFileSize(it, bytesCopied)
                    )
                )
            }
        }
    }

    class Builder {
        private val affectedPaths = mutableListOf<PathChange>()
        private val skipped = mutableListOf<APath>()
        private var copiedFiles: Int = 0
        private var copiedDirectories: Int = 0
        private var bytesCopied: Long = 0

        fun addCopiedItems(lookups: Collection<APathLookup<*>>) {
            val affected = lookups.map { lookup ->
                if (lookup.isDirectory) copiedDirectories++ else copiedFiles++
                PathChange(lookup.lookedUp, PathChange.Change.ADDED)
            }
            affectedPaths.addAll(affected)
        }

        fun setSkipped(items: Set<APath>) {
            skipped.addAll(items)
        }

        fun setBytesCopied(bytes: Long) {
            this.bytesCopied = bytes
        }

        fun build(): CopyOperationReport = CopyOperationReport(
            affectedPaths = affectedPaths.distinct(),
            skipped = skipped,
            copiedFiles = copiedFiles,
            copiedDirectories = copiedDirectories,
            bytesCopied = bytesCopied,
        )
    }
}