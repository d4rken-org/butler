package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.operations.TransferStrategy
import kotlinx.coroutines.CancellationException

/**
 * Moves on the server when both sides live on the same location, otherwise uploads and deletes the
 * source like any cross-type move.
 *
 * Atomic **directory** moves are owned by GenericPathMove (tryAtomicMove), not this strategy.
 */
class WebDavPathMoveStrategy(
    fileSystemOps: WebDavFileSystemOps,
) : TransferStrategy<WebDavPath, WebDavPathLookup, WebDavPath, WebDavPathLookup> {

    private val copyStrategy = WebDavUploadCopyStrategy<WebDavPath, WebDavPathLookup>(fileSystemOps)

    override suspend fun transferFile(
        sourceLookup: WebDavPathLookup,
        destination: WebDavPath,
        sourceOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        destOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<WebDavPath, WebDavPath> {
        log(TAG, DEBUG) { "Moving WebDAV file: ${sourceLookup.lookedUp} -> $destination" }

        try {
            when (val outcome = sourceOps.move(sourceLookup.lookedUp, destination)) {
                is MoveOutcome.Moved -> {
                    onProgress(sourceLookup.size ?: 0L)
                    return TransferStrategy.TransferResult.Success(
                        source = sourceLookup.lookedUp,
                        destination = destination,
                        bytesTransferred = sourceLookup.size ?: 0L,
                    )
                }

                is MoveOutcome.NotSupported -> {
                    log(TAG, DEBUG) { "Server-side move not possible (${outcome.reason}), copying instead" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PathAlreadyExistsException) {
            // A copy would run into the same destination
            throw e
        } catch (e: Exception) {
            // The move may have had side effects; only fall back if the source is verifiably intact.
            val sourceIntact = try {
                sourceOps.exists(sourceLookup.lookedUp)
            } catch (inner: CancellationException) {
                throw inner
            } catch (inner: Exception) {
                false
            }
            if (!sourceIntact) throw e
            log(TAG, DEBUG) { "Move failed with source intact, copying instead: ${e.message}" }
        }

        val copyResult = copyStrategy.transferFile(
            sourceLookup = sourceLookup,
            destination = destination,
            sourceOps = sourceOps,
            destOps = destOps,
            options = options,
            onProgress = onProgress,
        )

        if (copyResult !is TransferStrategy.TransferResult.Success) return copyResult

        try {
            val deleted = sourceOps.delete(sourceLookup.lookedUp)
            if (!deleted) log(TAG, WARN) { "Failed to delete source after copy: ${sourceLookup.lookedUp}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, ERROR) { "Error deleting source after copy: ${sourceLookup.lookedUp} - $e" }
        }

        return copyResult
    }

    override suspend fun createDirectory(
        sourceLookup: WebDavPathLookup,
        destination: WebDavPath,
        sourceOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        destOps: FileSystemOps<WebDavPath, WebDavPathLookup>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<WebDavPath, WebDavPath> = copyStrategy.createDirectory(
        sourceLookup = sourceLookup,
        destination = destination,
        sourceOps = sourceOps,
        destOps = destOps,
        options = options,
    )

    companion object {
        private val TAG = logTag("WebDAV", "MoveStrategy")
    }
}
