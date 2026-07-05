package eu.darken.butler.editor.core.sources

import java.io.IOException

/**
 * A commit failed AFTER the target may have been mutated and could not be restored: the on-disk
 * state no longer reliably matches the pre-commit content. Consumers must stop serving reads
 * based on pre-commit byte offsets and require a reload.
 */
class CommitIntegrityException(message: String, cause: Throwable) : IOException(message, cause)
