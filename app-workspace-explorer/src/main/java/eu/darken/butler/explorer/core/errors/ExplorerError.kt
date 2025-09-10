package eu.darken.butler.explorer.core.errors

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup

sealed class ExplorerError : Throwable() {
    data class ReadError(
        val path: APath,
        override val cause: Throwable? = null,
    ) : ExplorerError()

    data class WriteError(
        val path: APath,
        override val cause: Throwable? = null,
    ) : ExplorerError()

    data class FileConflict(
        val source: APathLookup<out APath>,
        val destination: APathLookup<out APath>,
    ) : ExplorerError()
}