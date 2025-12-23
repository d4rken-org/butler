package eu.darken.butler.common.files.errors

import eu.darken.butler.common.io.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import java.io.IOException

/**
 * Exception thrown when the IPC connection to a file service is lost.
 * This can happen when the service process dies (e.g., storage disconnected,
 * root/Shizuku service killed, or other system-level issues).
 */
class ServiceConnectionLostException(
    cause: Throwable? = null,
) : IOException("Service connection lost", cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.service_connection_lost_error_title.toCaString(),
        description = R.string.service_connection_lost_error_message.toCaString(),
    )
}
