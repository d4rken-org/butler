package eu.darken.butler.common.files.errors

import eu.darken.butler.common.io.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import java.io.IOException

/**
 * Exception thrown when the isolated file operations service process dies unexpectedly,
 * typically due to storage disconnection (USB unplugged, SD card ejected).
 */
class StorageDisconnectedException(
    cause: Throwable? = null,
) : IOException("Storage disconnected", cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.storage_disconnected_error_title.toCaString(),
        description = R.string.storage_disconnected_error_message.toCaString(),
    )
}
