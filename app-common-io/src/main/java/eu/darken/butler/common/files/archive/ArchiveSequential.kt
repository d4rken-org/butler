package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException

/** Caller's verdict for one sequentially delivered entry, feeds [SequentialResult] counters. */
enum class SequentialOutcome {
    EXTRACTED,

    /** Skipped by the operation's conflict policy (e.g. merge-skip-existing). */
    SKIPPED_POLICY,

    /** Skipped because an entry earlier in this same run supersedes it (dir-wins/self-collision). */
    SKIPPED_COLLISION,
}

/**
 * One entry as encountered during a sequential pass. [ordinal] is the entry's position in the
 * stream and the ONLY stable identity across restart passes - raw names may legally repeat.
 */
data class SequentialEntry(
    val ordinal: Int,
    val segments: List<String>,
    val rawName: String,
    val isEncrypted: Boolean,
)

data class SequentialResult(
    val extracted: Int,
    val skippedUnsafe: Int,
)

/**
 * A sequential pass hit a condition it cannot read or skip past (unsupported compression method,
 * streamed STORE entry with unknown size, container changed between passes, corrupted data).
 * Carries this pass's counters so operations can still report partial results.
 */
class SequentialAbortException(
    message: String,
    container: APath<*>,
    val extracted: Int,
    val skippedUnsafe: Int,
    cause: Throwable? = null,
) : ReadException(message, container, cause)
