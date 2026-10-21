package eu.darken.butler.common.files.archive

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException

/** The archive's structure or data failed the format's checks: truncated, corrupted or not an archive at all. */
class ArchiveDamagedException(
    val container: APath<*>,
    cause: Throwable? = null,
) : ReadException("Archive is damaged", container, cause) {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = "ArchiveDamagedException".toCaString(),
        description = caString { cx ->
            cx.getString(R.string.general_error_archive_damaged_msg, container.userReadablePath.get(cx))
        },
    )
}
