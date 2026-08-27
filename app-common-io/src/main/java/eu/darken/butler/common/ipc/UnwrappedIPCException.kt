package eu.darken.butler.common.ipc

import java.io.IOException

class UnwrappedIPCException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
