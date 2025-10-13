package eu.darken.butler.common.files.errors

import eu.darken.butler.common.files.APath
import java.io.IOException

sealed class PathException(
    message: String? = "Error during access.",
    val path: APath<*>?,
    cause: Throwable? = null,
) : IOException(if (path != null) "$message <-> ${path.path}" else message, cause)

