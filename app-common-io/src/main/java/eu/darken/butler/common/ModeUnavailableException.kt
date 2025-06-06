package eu.darken.butler.common

open class ModeUnavailableException(
    message: String? = null,
    cause: Throwable? = null
) : IllegalStateException(message, cause)