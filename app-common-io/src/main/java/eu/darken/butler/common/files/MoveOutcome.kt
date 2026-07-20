package eu.darken.butler.common.files

/**
 * Result of an atomic [FileSystemOps.move] attempt.
 *
 * Failures with possible side effects are signaled via exceptions, not via this type.
 */
sealed interface MoveOutcome {

    /** The document now exists at the destination under the requested name. */
    data object Moved : MoveOutcome

    /**
     * The move was not attempted or not supported — provably nothing was mutated.
     * Callers may safely fall back to copy+delete.
     */
    data class NotSupported(val reason: String) : MoveOutcome
}
