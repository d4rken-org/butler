package eu.darken.butler.common.root

import androidx.annotation.StringRes
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext

class RootUnavailableException @JvmOverloads constructor(
    message: String? = null,
    cause: Throwable? = null,
    @StringRes val errorMsgRes: Int = eu.darken.butler.common.R.string.general_error_root_unavailable
) : RootException(message = message, cause = cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "RootUnavailableException".toCaString(),
        description = errorMsgRes.toCaString()
    )
}