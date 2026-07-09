package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R
import java.io.IOException

/**
 * The backing file became unreadable while open (deleted or read permission lost). The piece
 * table can no longer materialize original bytes, so the document is served read-only from what
 * is still cached and edits/saves are refused until the file is reopened.
 */
class BackingUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_backing_lost_banner_title.toCaString(),
        description = R.string.editor_backing_lost_banner_message.toCaString(),
    )
}
