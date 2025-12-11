package eu.darken.butler.common.files.errors

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.APath

open class ReadException @JvmOverloads constructor(
    message: String? = "Can't read from path.",
    path: APath<*>? = null,
    cause: Throwable? = null,
) : PathException(message = message, cause = cause, path = path), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "ReadException".toCaString(),
        description = caString { cx ->
            val sb = StringBuilder()
            sb.append(
                path?.let {
                    cx.getString(
                        R.string.general_error_cant_access_msg,
                        it.userReadablePath.get(cx)
                    )
                } ?: message
            )
            cause?.let {
                sb.append("\n\n")
                val localizedCause = it.localized(cx)
                sb.append(localizedCause.asText().get(cx))
            }
            sb.toString()
        }
    )
}