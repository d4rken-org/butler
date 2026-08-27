package eu.darken.butler.common.files.errors

import eu.darken.butler.common.files.APath

/**
 * The path is positively known not to exist, as opposed to being unreadable.
 *
 * Constructed locally after an existence check answered false, never thrown across the IPC
 * boundary, and deliberately without a cause: the failure it replaces would otherwise be found
 * again when the cause chain is classified (see `PermissionErrorClassifierTest`).
 */
class PathNotFoundException(
    path: APath<*>,
) : ReadException(message = "Path does not exist", path = path)
