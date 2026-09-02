package eu.darken.butler.history.core

import android.content.Context
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.toJavaInstant

/**
 * Upper bound on the shared document. `EXTRA_TEXT` crosses the Binder boundary on `startActivity`,
 * whose transaction buffer is about 1 MB, and a select-all share can otherwise hold 2000 entries of
 * up to [OperationHistoryRepo.MAX_PATHS_PER_OP] paths each - tens of megabytes and a crash.
 */
internal const val SHARE_TEXT_MAX_CHARS = 100_000

private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

private const val BLOCK_SEPARATOR = "\n\n"

/**
 * Renders [entries] as markdown, one block per entry.
 *
 * [attemptedPaths] describes one specific entry, so it is only applied to a single-entry share; a
 * bulk share leaves it null rather than issuing one query per entry.
 *
 * [zone] is a parameter because the `Completed` line is a fixed pattern rather than a locale
 * format: a record meant to be pasted elsewhere should read the same everywhere, and a test can
 * pin the zone instead of depending on the machine's.
 */
internal fun buildHistoryShareText(
    context: Context,
    entries: List<HistoryEntry>,
    attemptedPaths: OperationHistoryRepo.AttemptedPaths? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamps = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN).withZone(zone)
    val entryPaths = attemptedPaths?.takeIf { entries.size == 1 }
    val document = StringBuilder()
    var written = 0

    for (entry in entries) {
        val block = entry.toShareBlock(context, entryPaths, timestamps)
        val separator = if (document.isEmpty()) "" else BLOCK_SEPARATOR
        val remaining = entries.size - (written + 1)
        // Budgeted per document, so one pathological entry is bounded too. A block that leaves
        // others behind also has to leave room for the notice announcing them, which is appended
        // after the loop and would otherwise push the document past the cap.
        val reserved = if (remaining > 0) {
            BLOCK_SEPARATOR.length + context.truncationNotice(remaining).length
        } else {
            0
        }
        if (document.length + separator.length + block.length + reserved > SHARE_TEXT_MAX_CHARS) break
        document.append(separator).append(block)
        written++
    }

    if (written < entries.size) {
        if (document.isNotEmpty()) document.append(BLOCK_SEPARATOR)
        document.append(context.truncationNotice(entries.size - written))
    }

    return document.toString()
}

private fun HistoryEntry.toShareBlock(
    context: Context,
    attemptedPaths: OperationHistoryRepo.AttemptedPaths?,
    timestamps: DateTimeFormatter,
): String = buildString {
    append("## ").append(singleLine(title)).append('\n')
    append(singleLine(description)).append('\n')
    append('\n')

    metaLine(context.getString(R.string.history_share_label_outcome), context.getString(outcome.labelRes))
    metaLine(context.getString(R.string.history_share_label_kind), context.getString(kind.labelRes))
    metaLine(context.getString(R.string.history_share_label_origin), context.getString(originType.labelRes))
    summary?.takeIf { it.isNotBlank() }?.let {
        metaLine(context.getString(R.string.history_share_label_summary), singleLine(it))
    }
    metaLine(
        context.getString(R.string.history_share_label_completed),
        timestamps.format(completedAt.toJavaInstant()),
    )
    metaLine(context.getString(R.string.history_share_label_duration), context.formatDuration(this@toShareBlock))
    errorMessage?.takeIf { it.isNotBlank() }?.let {
        metaLine(context.getString(R.string.history_share_label_error), singleLine(it))
    }
    if (partialErrorCount > 0) {
        metaLine(context.getString(R.string.history_share_label_failed_items), "$partialErrorCount")
    }

    append('\n')
    if (paths.isEmpty()) {
        append(context.getString(R.string.history_detail_paths_empty)).append('\n')
        val attempted = attemptedPaths?.paths.orEmpty()
        if (attempted.isNotEmpty()) {
            append('\n')
            append("**").append(context.getString(R.string.history_detail_label_attempted_paths)).append("**\n")
            val attemptedTotal = attemptedPaths?.totalCount ?: attempted.size
            if (attemptedTotal > attempted.size) {
                append(
                    context.getString(
                        R.string.history_detail_attempted_paths_truncated_callout,
                        attempted.size,
                        attemptedTotal,
                    )
                ).append('\n')
            }
            attempted.forEach { append("- ").append(codeSpan(it)).append('\n') }
        }
    } else {
        append("**").append(context.getString(R.string.history_share_label_paths, affectedPathsCount)).append("**\n")
        paths.forEach { change ->
            val previousPath = change.previousPath
            append("- ")
            if (previousPath != null) {
                append(codeSpan(previousPath)).append(" → ").append(codeSpan(change.path))
            } else {
                append(change.change.name.lowercase()).append(": ").append(codeSpan(change.path))
            }
            append('\n')
        }
        if (pathsTruncated) {
            append(context.getString(R.string.history_share_paths_more, affectedPathsCount - paths.size)).append('\n')
        }
    }
}.trimEnd('\n')

private fun Context.truncationNotice(remaining: Int): String =
    getString(R.string.history_share_truncated, remaining)

private fun StringBuilder.metaLine(label: String, value: String) {
    append("- **").append(label).append(":** ").append(value).append('\n')
}

private fun Context.formatDuration(entry: HistoryEntry): String {
    val ms = entry.duration.inWholeMilliseconds
    return if (ms < 1000L) {
        getString(R.string.history_duration_ms, ms.toInt())
    } else {
        getString(R.string.history_duration_seconds, ms / 1000.0f)
    }
}

/**
 * Paths and error text are arbitrary user data. A backtick inside a value would otherwise close the
 * span and corrupt the rest of the document, so the fence is one backtick longer than the longest
 * run in the value, padded when the value itself starts or ends with one.
 *
 * A line break ends the span outright and would leave the rendered path a truncated one, so CR and
 * LF are written as escapes instead. The backslash is escaped first, so `we\nird.txt` and a path
 * with a real newline stay distinguishable.
 */
private fun codeSpan(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    val longestRun = BACKTICK_RUN.findAll(escaped).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(longestRun + 1)
    val padding = if (escaped.startsWith('`') || escaped.endsWith('`')) " " else ""
    return "$fence$padding$escaped$padding$fence"
}

/**
 * A raw line break turns the rest of a single-line field into a stray paragraph, heading or list
 * item. CommonMark ends a line on a lone CR too, not just on LF and CRLF.
 */
private fun singleLine(value: String): String = value.replace(LINE_BREAK, " ")

private val BACKTICK_RUN = Regex("`+")
private val LINE_BREAK = Regex("\\r\\n?|\\n")
