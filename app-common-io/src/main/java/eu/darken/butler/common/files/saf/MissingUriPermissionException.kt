package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.ReadException

class MissingUriPermissionException(
    message: String? = "No matching UriPermission",
    path: APath? = null,
    cause: Throwable? = null,
) : ReadException(message = message, cause = cause, path = path)