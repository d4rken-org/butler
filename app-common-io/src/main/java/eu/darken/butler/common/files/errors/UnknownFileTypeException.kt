package eu.darken.butler.common.files.errors

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.APathLookup

/**
 * Exception thrown when a file operation encounters a file with UNKNOWN fileType.
 *
 * This typically occurs when:
 * - File exists but type cannot be determined due to permission restrictions
 * - File was created with elevated permissions (ROOT/ADB) but accessed with NORMAL permissions
 * - SELinux policies prevent type detection
 *
 * This exception is recognized as a permission error and triggers AUTO mode escalation.
 *
 * @param lookup The file lookup that has UNKNOWN fileType
 * @param cause The underlying exception that prevented type determination (optional)
 */
class UnknownFileTypeException(
    val lookup: APathLookup<*>,
    cause: Throwable? = null,
) : ReadException(
    message = "Unknown file type: ${lookup.fileType} for ${lookup.lookedUp.path}",
    path = lookup.lookedUp,
    cause = cause
), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "UnknownFileTypeException".toCaString(),
        description = caString { cx ->
            val sb = StringBuilder()
            sb.append(
                cx.getString(
                    R.string.general_error_cant_access_msg,
                    lookup.lookedUp.userReadablePath.get(cx)
                )
            )
            sb.append("\n\n")
            sb.append("File type: ${lookup.fileType}")
            cause?.let {
                sb.append("\n\n")
                val localizedCause = it.localized(cx)
                sb.append(localizedCause.asText().get(cx))
            }
            sb.toString()
        }
    )
}
