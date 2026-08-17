package eu.darken.butler.main.core.external

import eu.darken.butler.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext

/**
 * Butler could not act on a file another app handed over, e.g. the content was gone, unreadable or
 * could not be copied into the cache.
 */
class ExternalOpenFailedException(
    val fileName: String,
    cause: Throwable? = null,
) : IllegalStateException("Failed to open external file: $fileName", cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.external_open_failed_label.toCaString(),
        description = caString { it.getString(R.string.external_open_failed_description, fileName) },
    )
}
