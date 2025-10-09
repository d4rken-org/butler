package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup

/**
 * Strategy interface for transferring files (copy or move).
 *
 * Implementations define HOW files are transferred while the executor
 * handles WHEN and WHERE (scanning, space checking, error handling, etc.).
 */
interface TransferStrategy {

    /**
     * Transfer options that strategies may need.
     */
    data class Options(
        val preserveAttributes: Boolean = true,
        val followSymlinks: Boolean = false,
    )

    /**
     * Result of a transfer operation.
     */
    sealed class TransferResult {
        /**
         * Transfer completed successfully.
         *
         * @param source The source path
         * @param destination The actual destination path (may differ from requested if renamed)
         * @param bytesTransferred Number of bytes transferred
         */
        data class Success(
            val source: LocalPath,
            val destination: LocalPath,
            val bytesTransferred: Long
        ) : TransferResult()

        /**
         * Transfer was skipped (e.g., source == destination for move).
         */
        data class Skipped(
            val source: LocalPath,
            val reason: String
        ) : TransferResult()
    }

    /**
     * Transfers a file from source to destination.
     *
     * Implementations should:
     * - Handle the actual file transfer (copy or move)
     * - Handle symlinks according to options
     * - Report progress via onProgress callback
     * - Throw exceptions on errors (caller handles error resolution)
     *
     * @param sourceLookup Lookup information for the source
     * @param destination The destination path
     * @param options Transfer options
     * @param onProgress Callback for progress updates (bytes transferred)
     * @return TransferResult indicating success or skip
     * @throws Exception on transfer errors (handled by caller)
     */
    suspend fun transferFile(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferResult

    /**
     * Creates a directory at the destination.
     *
     * @param sourceLookup Lookup information for the source directory
     * @param destination The destination path
     * @param options Transfer options
     * @return TransferResult indicating success or skip
     * @throws Exception on creation errors (handled by caller)
     */
    suspend fun createDirectory(
        sourceLookup: LocalPathLookup,
        destination: LocalPath,
        options: Options
    ): TransferResult
}
