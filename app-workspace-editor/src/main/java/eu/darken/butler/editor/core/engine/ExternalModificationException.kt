package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.editor.R
import java.io.IOException

/** The file on disk no longer matches what the buffer loaded; saving would clobber foreign changes. */
class ExternalModificationException(message: String) : IOException(message), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.editor_external_change_banner_title.toCaString(),
        description = R.string.editor_error_external_change.toCaString(),
    )
}
