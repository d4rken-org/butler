package eu.darken.butler.common

import androidx.annotation.StringRes
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext

class RootRequiredException(
    message: String,
    cause: Throwable? = null,
    @StringRes val errorMsgRes: Int = R.string.general_error_root_unavailable
) : IllegalStateException(message, cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "RootRequiredException".toCaString(),
        description = errorMsgRes.toCaString()
    )
}