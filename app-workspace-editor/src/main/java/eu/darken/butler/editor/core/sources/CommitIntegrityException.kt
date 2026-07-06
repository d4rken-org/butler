package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R
import java.io.IOException

/**
 * A commit failed AFTER the target may have been mutated and could not be restored: the on-disk
 * state no longer reliably matches the pre-commit content. Consumers must stop serving reads
 * based on pre-commit byte offsets and require a reload.
 */
class CommitIntegrityException(message: String, cause: Throwable) : IOException(message, cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_error_save_integrity_label.toCaString(),
        description = R.string.editor_error_save_integrity.toCaString(),
    )
}
