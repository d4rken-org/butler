package eu.darken.butler.common.files

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.localized
import java.io.IOException

open class PathException(
    message: String? = "Error during access.",
    val path: APath?,
    cause: Throwable? = null,
) : IOException(if (path != null) "$message <-> ${path.path}" else message, cause)

open class ReadException @JvmOverloads constructor(
    message: String? = "Can't read from path.",
    path: APath? = null,
    cause: Throwable? = null,
) : PathException(message = message, cause = cause, path = path), HasLocalizedError {

    override fun getLocalizedError() = LocalizedError(
        throwable = this,
        label = "ReadException".toCaString(),
        description = caString { cx ->
            val sb = StringBuilder()
            sb.append(
                path?.let {
                    cx.getString(
                        eu.darken.butler.common.R.string.general_error_cant_access_msg,
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

class WriteException @JvmOverloads constructor(
    message: String? = "Can't write to path.",
    path: APath? = null,
    cause: Throwable? = null,
) : PathException(message = message, cause = cause, path = path), HasLocalizedError {

    override fun getLocalizedError() = LocalizedError(
        throwable = this,
        label = "WriteException".toCaString(),
        description = caString { cx ->
            val sb = StringBuilder()
            sb.append(
                path?.let {
                    cx.getString(
                        eu.darken.butler.common.R.string.general_error_cant_access_msg,
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