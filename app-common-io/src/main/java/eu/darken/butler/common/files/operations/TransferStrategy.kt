package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps

/**
 * Generic strategy interface for transferring files (copy or move).
 *
 * This interface defines HOW files are transferred, while the generic operation
 * classes (GenericPathCopy, GenericPathMove) handle WHEN and WHERE (scanning,
 * space checking, conflict resolution, error handling, progress tracking, etc.).
 *
 * ## Separation of Concerns
 *
 * **Strategy responsibilities:**
 * - Actual file transfer mechanics (streams, APIs, protocols)
 * - Path-specific optimizations (e.g., LocalPath uses Files.copy(), SAFPath uses streams)
 * - Attribute preservation (modtime, permissions)
 * - Progress reporting during transfer
 *
 * **Operation class responsibilities:**
 * - Tree scanning and traversal
 * - Conflict resolution (overwrite/skip/rename)
 * - Error handling and user prompts
 * - Space validation
 * - Overall progress tracking across all files
 *
 * ## Same-Type vs Cross-Type Operations
 *
 * This interface supports both same-type and cross-type operations:
 * - **Same-type**: SP = DP (e.g., SAFPath → SAFPath, LocalPath → LocalPath)
 *   - sourceOps and destOps are the same instance
 *   - Used within individual gateways
 * - **Cross-type**: SP ≠ DP (e.g., SAFPath → LocalPath, FTP → LocalPath)
 *   - sourceOps and destOps are different instances
 *   - Uses stream-based transfer between path types
 *
 * ## Implementations
 *
 * **Same-type strategies:**
 * - **LocalPathCopyStrategy**: Uses java.nio.Files.copy() with COPY_ATTRIBUTES
 * - **LocalPathMoveStrategy**: Uses Files.move() with ATOMIC_MOVE fallback
 * - **SAFPathCopyStrategy**: Uses ContentResolver streams with manual attribute copy
 * - **SAFPathMoveStrategy**: Uses DocumentsContract.moveDocument() or copy+delete fallback
 *
 * **Cross-type strategies:**
 * - **SAFToLocalCopyStrategy**: Streams from SAF ContentResolver to LocalPath Files API
 * - **LocalToSAFCopyStrategy**: Streams from LocalPath Files API to SAF ContentResolver
 * - **FtpToLocalCopyStrategy**: Uses FTP RETR to LocalPath (future)
 *
 * @param SP The source path type (LocalPath, SAFPath, etc.)
 * @param SPL The source path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 * @param DP The destination path type (LocalPath, SAFPath, etc.)
 * @param DPL The destination path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 */
interface TransferStrategy<
    SP : APath<SP>, SPL : APathLookup<SP>,  // Source types
    DP : APath<DP>, DPL : APathLookup<DP>   // Destination types
> {

    /**
     * Transfer options that strategies may need.
     */
    data class Options(
        /**
         * Whether to preserve file attributes (modtime, permissions, ownership).
         * Implementation-dependent - some file systems may not support all attributes.
         */
        val preserveAttributes: Boolean = true,

        /**
         * Whether to follow symlinks to their targets during transfer.
         * If true, symlinks are resolved and their targets are transferred.
         * If false, symlinks are transferred as symlinks (if supported).
         */
        val followSymlinks: Boolean = false,

        /**
         * Whether to overwrite existing destination files.
         * Note: Conflict resolution is typically handled by the operation class,
         * but strategies may use this for atomic operations.
         */
        val overwrite: Boolean = false,
    )

    /**
     * Result of a transfer operation.
     *
     * Supports both same-type (SP=DP) and cross-type (SP≠DP) transfers.
     *
     * Note: Limited to 2 type parameters due to KAPT limitations with >2 generics.
     * The destinationLookup uses the base APathLookup<DP> type instead of a specific
     * lookup type parameter (DPL) to work around this limitation.
     *
     * @param SP Source path type
     * @param DP Destination path type
     */
    sealed class TransferResult<SP : APath<SP>, DP : APath<DP>> {
        /**
         * Transfer completed successfully.
         *
         * @param source The source path
         * @param destination The actual destination path (may differ from requested if renamed)
         * @param bytesTransferred Number of bytes transferred
         * @param destinationLookup Optional lookup of created destination (avoids redundant stat)
         */
        data class Success<SP : APath<SP>, DP : APath<DP>>(
            val source: SP,
            val destination: DP,
            val bytesTransferred: Long,
            val destinationLookup: APathLookup<DP>? = null
        ) : TransferResult<SP, DP>()

        /**
         * Transfer was skipped.
         *
         * Examples:
         * - Source and destination are identical for move operations
         * - File was filtered out by user callback
         * - Strategy-specific skip conditions
         *
         * @param source The source path
         * @param reason Human-readable reason for skip
         */
        data class Skipped<SP : APath<SP>, DP : APath<DP>>(
            val source: SP,
            val reason: String
        ) : TransferResult<SP, DP>()
    }

    /**
     * Transfer a file from source to destination.
     *
     * This method handles the actual file content transfer. It should:
     * - Transfer file contents using appropriate API for the path type
     * - Report progress via onProgress callback (incremental bytes, not total)
     * - Preserve attributes if requested in options
     * - Handle symlinks according to options.followSymlinks
     * - Throw exceptions on errors (caller handles error resolution)
     *
     * The method should NOT:
     * - Handle conflicts (overwrite/skip decisions) - caller handles via onIssue
     * - Create parent directories - caller ensures destination parent exists
     * - Handle overall progress tracking - caller tracks across all files
     *
     * For same-type operations (SP=DP):
     * - sourceOps and destOps will be the same instance
     * - Can use optimized platform-specific APIs (e.g., Files.copy(), moveDocument())
     *
     * For cross-type operations (SP≠DP):
     * - sourceOps and destOps will be different instances
     * - Use stream-based transfer: sourceOps.openInputStream() → destOps.openOutputStream()
     * - Attribute preservation limited by least capable file system
     *
     * @param sourceLookup Lookup information for the source file
     * @param destination The destination path (parent directory guaranteed to exist)
     * @param sourceOps File system operations for the source path type
     * @param destOps File system operations for the destination path type
     * @param options Transfer options
     * @param onProgress Callback for progress updates (incremental bytes transferred, not cumulative)
     * @return TransferResult indicating success or skip
     * @throws eu.darken.butler.common.files.errors.ReadException if source cannot be read
     * @throws eu.darken.butler.common.files.errors.WriteException if destination cannot be written
     */
    suspend fun transferFile(
        sourceLookup: SPL,
        destination: DP,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<DP, DPL>,
        options: Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferResult<SP, DP>

    /**
     * Create a directory at the destination.
     *
     * This method creates a directory and optionally copies attributes from source.
     * It should:
     * - Create the directory
     * - Preserve attributes if requested in options
     * - Be idempotent (no error if directory already exists)
     * - Throw exceptions on errors (caller handles error resolution)
     *
     * The method should NOT:
     * - Create parent directories - caller ensures destination parent exists
     * - Handle conflicts - caller handles via onIssue
     * - List or copy children - caller handles tree traversal
     *
     * For same-type operations (SP=DP):
     * - sourceOps and destOps will be the same instance
     * - Can use optimized platform-specific APIs
     *
     * For cross-type operations (SP≠DP):
     * - sourceOps and destOps will be different instances
     * - Use destOps.createDir() and copy attributes if supported
     *
     * @param sourceLookup Lookup information for the source directory
     * @param destination The destination path (parent directory guaranteed to exist)
     * @param sourceOps File system operations for the source path type
     * @param destOps File system operations for the destination path type
     * @param options Transfer options
     * @return TransferResult indicating success or skip
     * @throws eu.darken.butler.common.files.errors.WriteException if directory cannot be created
     */
    suspend fun createDirectory(
        sourceLookup: SPL,
        destination: DP,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<DP, DPL>,
        options: Options
    ): TransferResult<SP, DP>
}
