package eu.darken.butler.common.files.errors

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.io.R

/**
 * A rename of [path] that only changes the letter case, `photos` to `Photos`, on storage where it
 * could not be done safely. Thrown before anything was touched.
 *
 * Deliberately without a cause: a denied listing in the chain would otherwise be classified as a
 * permission failure (see `PermissionErrorClassifierTest`).
 */
class CaseOnlyRenameException(
    path: APath<*>,
    val sourceName: String,
    val destinationName: String,
) : WriteException(
    message = "Refused case-only rename of $sourceName to $destinationName",
    path = path,
) {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.path_action_unknown_error_title.toCaString(),
        description = caString {
            it.getString(R.string.path_action_case_only_rename_unsupported_msg, sourceName, destinationName)
        },
    )
}
