package eu.darken.butler.common.files.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import kotlinx.coroutines.CancellationException
import okio.buffer
import okio.sink
import okio.source

/**
 * Generic strategy for copying files across different path types.
 *
 * This strategy works for ANY cross-type combination:
 * - Local ↔ SAF
 * - Local ↔ FTP
 * - SAF ↔ FTP
 * - FTP ↔ SMB
 * - etc.
 *
 * ## Why Generic?
 *
 * Cross-type operations cannot use platform-specific optimizations (like Files.copy() or
 * DocumentsContract.moveDocument()) because source and destination are different types.
 * The ONLY option is stream-based transfer: openInputStream() → openOutputStream().
 *
 * Since all cross-type operations use identical logic, a generic implementation:
 * - Eliminates O(n²) class explosion (no LocalToSAF, SAFToLocal, FTPToLocal, etc.)
 * - Automatically supports new path types (FTP, SMB) without new strategy classes
 * - Reduces code duplication (100+ lines × 4+ classes → single implementation)
 *
 * ## Stream-Based Transfer
 *
 * Uses Okio for efficient buffered I/O:
 * 1. sourceOps.openInputStream() → Okio Source
 * 2. destOps.openOutputStream() → Okio Sink
 * 3. Copy bytes in 64KB chunks with progress reporting
 *
 * ## Attribute Preservation
 *
 * Uses best-effort approach:
 * - Modified time: Attempted for all combinations (most widely supported)
 * - Permissions: Attempted (silently ignored if not supported)
 * - Ownership: Attempted (silently ignored if not supported or requires root)
 *
 * File systems that don't support an attribute simply return false from setXxx() methods.
 *
 * @param SP The source path type (LocalPath, SAFPath, FTPPath, etc.)
 * @param SPL The source path lookup type
 * @param DP The destination path type (LocalPath, SAFPath, FTPPath, etc.)
 * @param DPL The destination path lookup type
 */
class GenericCrossTypeCopyStrategy<
    SP : APath<SP>, SPL : APathLookup<SP>,
    DP : APath<DP>, DPL : APathLookup<DP>
    > : TransferStrategy<SP, SPL, DP, DPL> {

    override suspend fun transferFile(
        sourceLookup: SPL,
        destination: DP,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<DP, DPL>,
        options: TransferStrategy.Options,
        onProgress: suspend (bytesTransferred: Long) -> Unit
    ): TransferStrategy.TransferResult<SP, DP> {
        log(TAG, DEBUG) { "Copying cross-type: ${sourceLookup.lookedUp} → $destination" }

        // Create destination file (parent exists from depth-first traversal)
        destOps.createFile(destination)

        var totalBytesTransferred = 0L

        // Copy file contents using streams
        sourceOps.openInputStream(sourceLookup.lookedUp).source().buffer().use { source ->
            destOps.openOutputStream(destination, append = false).sink().buffer().use { sink ->
                val buffer = okio.Buffer()
                var bytesRead: Long

                while (source.read(buffer, BUFFER_SIZE.toLong()).also { bytesRead = it } != -1L) {
                    sink.write(buffer, bytesRead)
                    totalBytesTransferred += bytesRead
                    onProgress(bytesRead)
                }
                sink.flush()
            }
        }

        // Copy attributes (best-effort, limited by what both support) and capture lookup
        val destLookup = if (options.preserveAttributes) {
            copyCompatibleAttributes(sourceLookup.lookedUp, destination, sourceOps, destOps)
        } else {
            // Lookup created destination to avoid redundant stat in caller
            destOps.lookup(destination, LookupOptions.BASE)
        }

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = totalBytesTransferred,
            destinationLookup = destLookup
        )
    }

    override suspend fun createDirectory(
        sourceLookup: SPL,
        destination: DP,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<DP, DPL>,
        options: TransferStrategy.Options
    ): TransferStrategy.TransferResult<SP, DP> {
        log(TAG, DEBUG) { "Copying directory cross-type: ${sourceLookup.lookedUp} → $destination ($options)" }

        // Create directory at destination (parent exists from depth-first traversal)
        destOps.createDir(destination)

        // Copy attributes if requested and capture lookup
        val destLookup = if (options.preserveAttributes) {
            copyCompatibleAttributes(sourceLookup.lookedUp, destination, sourceOps, destOps)
        } else {
            // Lookup created destination to avoid redundant stat in caller
            destOps.lookup(destination, LookupOptions.BASE)
        }

        return TransferStrategy.TransferResult.Success(
            source = sourceLookup.lookedUp,
            destination = destination,
            bytesTransferred = 0L,
            destinationLookup = destLookup
        )
    }

    /**
     * Copy attributes that are supported by both source and destination file systems.
     *
     * Uses best-effort approach:
     * - Each attribute is attempted individually
     * - Failures are logged but don't fail the operation
     * - If file system doesn't support an attribute, setXxx() returns false
     *
     * ## Attribute Portability
     *
     * | Attribute | Local | SAF | FTP (typical) | SMB (typical) |
     * |-----------|-------|-----|---------------|---------------|
     * | Modified time | ✅ | ✅ | ✅ | ✅ |
     * | Permissions | ✅ | ❌ | ✅ | ✅ |
     * | Ownership | ✅ | ❌ | ✅ | ✅ |
     *
     * Modified time is the most portable and is attempted for all combinations.
     *
     * @return Lookup of destination with MAX options (avoids redundant stat in caller)
     */
    private suspend fun copyCompatibleAttributes(
        source: SP,
        destination: DP,
        sourceOps: FileSystemOps<SP, SPL>,
        destOps: FileSystemOps<DP, DPL>
    ): APathLookup<DP> {
        try {
            val sourceExtended = sourceOps.lookup(source, LookupOptions.MAX)

            // Try modified time (most widely supported)
            sourceExtended.modifiedAt?.let { modTime ->
                val success = destOps.setModifiedAt(destination, modTime)
                if (!success) {
                    log(TAG, VERBOSE) { "Failed to set modified time for $destination" }
                }
            }

            // Try permissions (POSIX file systems)
            sourceExtended.permissions?.let { perms ->
                val success = destOps.setPermissions(destination, perms)
                if (!success) {
                    log(TAG, VERBOSE) { "Permissions not supported for $destination" }
                }
            }

            // Try ownership (requires elevated privileges on most systems)
            sourceExtended.ownership?.let { owner ->
                val success = destOps.setOwnership(destination, owner)
                if (!success) {
                    log(TAG, VERBOSE) { "Ownership not supported for $destination" }
                }
            }

            log(TAG, VERBOSE) { "Cross-type attribute copy completed (best-effort)" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to copy attributes: $e" }
            // Don't fail the operation - attribute copying is best-effort
        }

        // Lookup destination to return (and avoid redundant stat in caller)
        return destOps.lookup(destination, LookupOptions.MAX)
    }

    companion object {
        private val TAG = logTag("PathOperation", "GenericCrossTypeCopy")
        private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks
    }
}
