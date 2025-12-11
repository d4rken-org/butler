package eu.darken.butler.common.ipc

import android.os.DeadObjectException
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.error.getStackTracePeek

class ServiceConnectionLostException(
    override val cause: DeadObjectException
) : Exception(), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext): LocalizedError = LocalizedError(
        throwable = this,
        label = R.string.general_error_ipc_deadobject_title.toCaString(),
        description = caString {
            var message = it.getString(R.string.general_error_ipc_deadobject_description)
            message += "\n\n"
            message += cause.getStackTracePeek()
            message
        }
    )

}