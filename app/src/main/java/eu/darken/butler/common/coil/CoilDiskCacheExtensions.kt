package eu.darken.butler.common.coil

import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache

/**
 * Extension function for safe DiskCache.Editor usage.
 * Automatically commits on success or aborts on exception.
 */
@OptIn(ExperimentalCoilApi::class)
inline fun <T : DiskCache.Editor?, R> T.use(block: (T) -> R): R {
    try {
        return block(this).also { this?.commit() }
    } catch (e: Exception) {
        this?.abort()
        throw e
    }
}
