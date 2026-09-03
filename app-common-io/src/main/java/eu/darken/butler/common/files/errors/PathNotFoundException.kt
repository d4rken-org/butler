package eu.darken.butler.common.files.errors

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.io.R

/**
 * The path is positively known not to exist, as opposed to being unreadable.
 *
 * Constructed after an existence check answered a definitive ABSENT, both by the in-process lookup
 * and by the one running inside a privileged host, where it crosses the binder as
 * `IpcErrorCode.PATH_NOT_FOUND`.
 *
 * Deliberately without a cause: the failure it replaces would otherwise be found again when the
 * cause chain is classified (see `PermissionErrorClassifierTest`).
 */
class PathNotFoundException(
    path: APath<*>,
) : ReadException(message = "Path does not exist", path = path), PathGoneError {

    // Without this the inherited label renders as the literal class name "ReadException".
    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.path_action_not_found_title.toCaString(),
        description = caString {
            val resolved = path!!
            it.getString(
                R.string.path_action_not_found_description,
                resolved.name.ifBlank { resolved.path },
            )
        },
    )
}
