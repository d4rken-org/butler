package eu.darken.butler.common.files.archive

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException

/** A multi-part archive needs a part that could not be opened. [partName] is its file name, null if unknown. */
class ArchiveMissingPartException(
    val container: APath<*>,
    val partName: String?,
    cause: Throwable? = null,
) : ReadException("Archive part is missing: ${partName ?: "unknown"}", container, cause) {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "ArchiveMissingPartException".toCaString(),
        description = caString { cx ->
            val archive = container.userReadablePath.get(cx)
            if (partName != null) {
                cx.getString(R.string.general_error_archive_missing_part_msg, archive, partName)
            } else {
                cx.getString(R.string.general_error_archive_missing_part_unknown_msg, archive)
            }
        },
    )
}
