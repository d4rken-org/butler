package eu.darken.butler.common.files.errors

import eu.darken.butler.common.files.APath

/**
 * Thrown when attempting to create a file or directory that already exists.
 *
 * This exception provides type-safe detection of path collisions, allowing
 * operations like copy/move to handle conflicts appropriately (skip/overwrite/rename).
 */
class PathAlreadyExistsException(
    message: String? = null,
    path: APath,
    cause: Throwable? = null
) : WriteException(message ?: "Path already exists: $path", path, cause)
