package eu.darken.butler.saver.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.operations.Operation

data class SaveFilesReport(
    val results: List<FileResult>,
    override val performanceHistory: PerformanceHistory? = null,
) : Operation.Report, Operation.HasPerformanceHistory {

    sealed class FileResult {
        abstract val filename: String

        data class Success(
            override val filename: String,
            val savedPath: APath<*>,
            val bytes: Long,
        ) : FileResult()

        data class Skipped(
            override val filename: String,
            val reason: SkipReason,
        ) : FileResult() {
            enum class SkipReason { USER_SKIPPED, PERMISSION_DENIED, CONFLICT }
        }

        data class Error(
            override val filename: String,
            val error: Throwable,
        ) : FileResult()
    }

    val successes: List<FileResult.Success>
        get() = results.filterIsInstance<FileResult.Success>()

    val skipped: List<FileResult.Skipped>
        get() = results.filterIsInstance<FileResult.Skipped>()

    val errors: List<FileResult.Error>
        get() = results.filterIsInstance<FileResult.Error>()

    val totalBytesWritten: Long
        get() = successes.sumOf { it.bytes }

    override val summary: CaString = caString { cx ->
        when {
            errors.isEmpty() && skipped.isEmpty() -> cx.getQuantityString2(
                R.plurals.saver_success_count,
                successes.size,
                successes.size,
            )
            successes.isEmpty() && skipped.isEmpty() -> cx.getQuantityString2(
                R.plurals.saver_error_count,
                errors.size,
                errors.size,
            )
            successes.isEmpty() && errors.isEmpty() -> cx.getQuantityString2(
                R.plurals.saver_skipped_count,
                skipped.size,
                skipped.size,
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

    /**
     * A save takes many files, so the label is pinned to the first PLANNED file rather than to
     * whichever one happened to succeed: `[a.txt, b.txt]` with `a.txt` skipped names `b.txt`.
     */
    override val subjectPath: APath<*>?
        get() = (results.firstOrNull() as? FileResult.Success)?.savedPath
            ?: successes.firstOrNull()?.savedPath

    /**
     * Per-file failures (errors). Skipped files are user-acknowledged choices and not counted.
     * When `errors` is non-empty alongside successes, [HistoryOutcome.PARTIAL] is recorded.
     */
    override val partialErrorCount: Int
        get() = errors.size
}
