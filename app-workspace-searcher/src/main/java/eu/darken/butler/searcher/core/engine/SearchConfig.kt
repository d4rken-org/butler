package eu.darken.butler.searcher.core.engine

import kotlin.time.Duration.Companion.milliseconds

/**
 * Configuration constants for the search engine.
 * Extracted from inline magic numbers for clarity and testability.
 */
object SearchConfig {
    /** Publish search results to observers in batches of at most this many items… */
    const val RESULT_BATCH_SIZE = 64

    /** …or once this much time has passed since the batch's first item, whichever comes first */
    val RESULT_BATCH_INTERVAL = 100.milliseconds

    /** Maximum file size to search content within (10MB) */
    const val MAX_CONTENT_FILE_SIZE = 10L * 1024 * 1024

    /** Buffer size for reading file content (128KB) */
    const val CONTENT_READ_BUFFER = 128 * 1024

    /** Report progress every N items scanned */
    const val PROGRESS_UPDATE_INTERVAL = 100

    /** Number of context lines to capture before a match */
    const val CONTEXT_LINES_BEFORE = 2

    /** Number of context lines to capture after a match */
    const val CONTEXT_LINES_AFTER = 2

    /** Maximum length for context lines to avoid memory issues */
    const val MAX_CONTEXT_LINE_LENGTH = 500
}
