package eu.darken.butler.saver.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.operations.Operation

data class SaveFilesReport(
    val results: List<FileResult>,
) : Operation.Report {

    sealed class FileResult {
        abstract val filename: String

        data class Success(
            override val filename: String,
            val savedPath: APath<*>,
            val bytes: Long,
        ) : FileResult()

        data class Error(
            override val filename: String,
            val error: Throwable,
        ) : FileResult()
    }

    val successes: List<FileResult.Success>
        get() = results.filterIsInstance<FileResult.Success>()

    val errors: List<FileResult.Error>
        get() = results.filterIsInstance<FileResult.Error>()

    val totalBytesWritten: Long
        get() = successes.sumOf { it.bytes }

    override val summary: CaString = caString { cx ->
        when {
            errors.isEmpty() -> cx.getQuantityString2(
                R.plurals.saver_success_count,
                successes.size,
                successes.size,
            )
            successes.isEmpty() -> cx.getQuantityString2(
                R.plurals.saver_error_count,
                errors.size,
                errors.size,
            )
            else -> cx.getString(
                R.string.saver_partial_success,
                successes.size,
                results.size,
            )
        }
    }

    override val affectedPaths: Collection<Operation.Report.PathChange>
        get() = successes.map {
            Operation.Report.PathChange(it.savedPath, Operation.Report.PathChange.Change.ADDED)
        }
}
