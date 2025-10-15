package eu.darken.butler.common.files

import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import okio.FileHandle
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant

/**
 * Abstraction layer for file system operations.
 *
 * This interface encapsulates platform-specific dependencies (Android Context,
 * ContentResolver, file system APIs, etc.) to enable testability without requiring
 * the actual platform framework.
 *
 * ## Design Goals
 *
 * 1. **Testability**: Mock implementations can simulate file system behavior in-memory
 *    without requiring Android framework or real file system access.
 *
 * 2. **Platform Abstraction**: Different path types use different underlying APIs:
 *    - LocalPath: java.nio.Files API
 *    - SAFPath: Android DocumentsContract + ContentResolver
 *    - FtpPath: FTP client library
 *    - SmbPath: SMB client library
 *
 * 3. **Dependency Encapsulation**: Platform-specific dependencies (Context,
 *    ContentResolver, UriPermissions, connection pools) are hidden in the
 *    implementation, not passed through every operation.
 *
 * ## Implementations
 *
 * - **LocalFileSystemOps**: Uses java.nio.Files API and LocalPath extensions
 * - **SAFFileSystemOps**: Wraps Context/ContentResolver/UriPermissions for SAF operations
 * - **FtpFileSystemOps**: Wraps FTP client and connection pool (future)
 * - **MockFileSystemOps**: In-memory implementation for unit testing
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Production code
 * val safOps = SAFFileSystemOps(context, contentResolver) { uriPermissions }
 * val lookup = safOps.lookup(path)
 *
 * // Test code (no Android framework needed!)
 * val mockOps = MockFileSystemOps<SAFPath, SAFPathLookup>()
 * mockOps.addMockFile("/test.txt", "content".toByteArray())
 * val lookup = mockOps.lookup(testPath)
 * ```
 *
 * @param P The path type (LocalPath, SAFPath, etc.)
 * @param PL The path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 * @param PLE The path lookup extended type (LocalPathLookupExtended, SAFPathLookupExtended, etc.)
 */
interface FileSystemOps<P : APath<P>, PL : APathLookup<P>, PLE : APathLookupExtended<P>> {

    /**
     * Look up metadata for a single path.
     *
     * Returns a lookup object containing file metadata (size, type, permissions, etc.).
     * Does not follow symlinks unless the implementation specifically documents that it does.
     *
     * @param path The path to look up
     * @return Path lookup with metadata
     * @throws eu.darken.butler.common.files.errors.ReadException if path cannot be read or doesn't exist
     */
    suspend fun lookup(path: P): PL

    /**
     * List immediate children of a directory.
     *
     * Returns only direct children, not recursive. Order is implementation-defined
     * (typically alphabetical or file system order).
     *
     * @param path The directory path to list
     * @return List of child paths (empty if directory is empty)
     * @throws eu.darken.butler.common.files.errors.ReadException if path cannot be read, doesn't exist, or is not a directory
     */
    suspend fun listFiles(path: P): List<P>

    /**
     * List directory contents with metadata in a single operation.
     *
     * This is an optimization to avoid N+1 calls (listFiles + N lookups).
     * Particularly important for cross-process operations (ROOT/ADB) where each
     * IPC call has overhead.
     *
     * Default implementation calls listFiles() and maps each to lookup(),
     * but implementations should override with a single optimized call when possible.
     *
     * @param path The directory path to list
     * @return List of path lookups for all children (empty if directory is empty)
     * @throws eu.darken.butler.common.files.errors.ReadException if path cannot be read, doesn't exist, or is not a directory
     */
    suspend fun lookupFiles(path: P): List<PL> {
        return listFiles(path).map { lookup(it) }
    }

    /**
     * Look up extended metadata for a single path.
     *
     * Returns extended metadata including permissions, ownership, and creation time when available.
     * This is slower than basic lookup() because it requires additional system calls.
     *
     * **Always returns an object** - individual fields may be null if not supported by the file system:
     * - `permissions`: null if file system doesn't support POSIX permissions (e.g., SAF, FAT32)
     * - `ownership`: null if not available or requires elevated privileges
     * - `createdAt`: null if file system doesn't track creation time (e.g., ext4 on old kernels)
     *
     * ## Performance Notes
     *
     * - Slower than basic lookup() - involves additional syscalls
     * - UI should use basic lookup() for list display, extended only when needed
     * - Generic operations use this for attribute preservation (permissions, ownership)
     *
     * ## Portability
     *
     * Portable attributes (basic read/write/execute) can be preserved cross-type.
     * Non-portable attributes (UIDs, ACLs, extended attributes) return null.
     *
     * @param path The path to look up
     * @return Extended path lookup (never null, but fields may be null)
     * @throws eu.darken.butler.common.files.errors.ReadException if path cannot be read or doesn't exist
     */
    suspend fun lookupExtended(path: P): PLE

    /**
     * List directory contents with extended metadata in a single operation.
     *
     * Combines listFiles() + extended lookup for all children.
     * Even slower than lookupFiles() due to extended metadata queries.
     *
     * **Performance optimization for batch operations**: Implementations should override
     * with a single optimized batch call when possible to reduce IPC overhead.
     *
     * Default implementation calls listFiles() and maps each to lookupExtended(),
     * but implementations should batch when possible (e.g., single fstat call for all).
     *
     * @param path The directory path to list
     * @return List of extended path lookups for all children (empty if directory is empty)
     * @throws eu.darken.butler.common.files.errors.ReadException if path cannot be read, doesn't exist, or is not a directory
     */
    suspend fun lookupFilesExtended(path: P): List<PLE> {
        return listFiles(path).map { lookupExtended(it) }
    }

    /**
     * Check if a path exists.
     *
     * Does not follow symlinks - checks if the symlink itself exists, not its target.
     * Does not throw exceptions - returns false if path doesn't exist or cannot be accessed.
     *
     * @param path The path to check
     * @return true if path exists, false otherwise
     */
    suspend fun exists(path: P): Boolean

    /**
     * Delete a file, symlink, or directory.
     *
     * @param path The path to delete
     * @param recursive If true, recursively delete directory contents (post-order: children before parents).
     *                  If false, directories must be empty to be deleted.
     * @return true if deleted successfully, false if path didn't exist
     * @throws eu.darken.butler.common.files.errors.WriteException if deletion fails (e.g., directory not empty when recursive=false, permission denied)
     *
     * ## Platform Optimization
     * Implementations should use platform-specific optimizations when recursive=true:
     * - LocalPath: Use Files.walkFileTree() with delete visitor
     * - Root: Use `rm -rf` shell command
     * - SAFPath: Recursively walk DocumentFile tree
     *
     * ## Usage Notes
     * - For user-facing deletion with progress and error handling, use GenericPathDelete operation class
     * - This is a low-level primitive for internal use by operations (e.g., Move/Copy overwrite)
     */
    suspend fun delete(path: P, recursive: Boolean = false): Boolean

    /**
     * Create a directory, including parent directories if needed.
     *
     * Similar to `mkdir -p` - creates all missing parent directories.
     * Does not fail if directory already exists (idempotent).
     *
     * @param path The directory path to create
     * @throws eu.darken.butler.common.files.errors.PathAlreadyExistsException if path exists as a file
     * @throws eu.darken.butler.common.files.errors.WriteException if creation fails for other reasons
     */
    suspend fun createDir(path: P)

    /**
     * Create an empty file.
     *
     * Creates parent directories if needed.
     * Fails if file already exists (not idempotent like createDir).
     *
     * @param path The file path to create
     * @throws eu.darken.butler.common.files.errors.PathAlreadyExistsException if file already exists
     * @throws eu.darken.butler.common.files.errors.WriteException if creation fails for other reasons
     */
    suspend fun createFile(path: P)

    /**
     * Create a symbolic link.
     *
     * Creates a symbolic link at linkPath that points to targetPath.
     * Some file systems (like SAF) do not support symlinks and will throw UnsupportedOperationException.
     *
     * @param linkPath The path where the symlink should be created
     * @param targetPath The path the symlink should point to (can be relative or absolute)
     * @return true if symlink was created successfully
     * @throws eu.darken.butler.common.files.errors.WriteException if symlink creation fails
     * @throws UnsupportedOperationException if file system doesn't support symlinks
     */
    suspend fun createSymlink(linkPath: P, targetPath: P): Boolean

    /**
     * Read the target of a symbolic link.
     *
     * Returns the target path that the symlink points to (which may be relative or absolute).
     * Some file systems (like SAF) do not support symlinks and will throw UnsupportedOperationException.
     *
     * @param linkPath The symlink path to read
     * @return The target path the symlink points to
     * @throws eu.darken.butler.common.files.errors.ReadException if symlink cannot be read or doesn't exist
     * @throws UnsupportedOperationException if file system doesn't support symlinks
     */
    suspend fun readSymbolicLink(linkPath: P): P

    /**
     * Move/rename a file or directory.
     *
     * Attempts atomic move when source and destination are on the same file system.
     * Falls back to copy+delete if atomic move is not possible.
     *
     * @param source The source path to move
     * @param destination The destination path
     * @return true if moved successfully
     * @throws eu.darken.butler.common.files.errors.WriteException if move fails
     */
    suspend fun move(source: P, destination: P): Boolean

    /**
     * Open input stream for reading file contents.
     *
     * Caller is responsible for closing the stream.
     * Stream is positioned at the beginning of the file.
     *
     * @param path The file path to read
     * @return InputStream positioned at file start
     * @throws eu.darken.butler.common.files.errors.ReadException if file cannot be opened or doesn't exist
     */
    suspend fun openInputStream(path: P): InputStream

    /**
     * Open output stream for writing file contents.
     *
     * Caller is responsible for closing the stream.
     * Creates parent directories if needed.
     *
     * @param path The file path to write
     * @param append If true, append to existing file; if false, truncate existing content
     * @return OutputStream for writing
     * @throws eu.darken.butler.common.files.errors.WriteException if file cannot be opened
     */
    suspend fun openOutputStream(path: P, append: Boolean = false): OutputStream

    suspend fun file(path: P, readWrite: Boolean): FileHandle

    /**
     * Set last modified timestamp.
     *
     * Some file systems or path types may not support this operation.
     *
     * @param path The path to modify
     * @param modifiedAt The new timestamp
     * @return true if successful, false if not supported or failed
     */
    suspend fun setModifiedAt(path: P, modifiedAt: Instant): Boolean

    /**
     * Set file permissions (Unix-style mode bits).
     *
     * Some file systems or path types may not support Unix permissions.
     *
     * @param path The path to modify
     * @param permissions The permissions to set
     * @return true if successful, false if not supported or failed
     */
    suspend fun setPermissions(path: P, permissions: Permissions): Boolean

    /**
     * Set file ownership (Unix-style UID/GID).
     *
     * Some file systems or path types may not support Unix ownership.
     * Typically requires elevated privileges (root/ADB).
     *
     * @param path The path to modify
     * @param ownership The ownership to set
     * @return true if successful, false if not supported or failed
     */
    suspend fun setOwnership(path: P, ownership: Ownership): Boolean

    /**
     * Check if path is readable.
     *
     * Default implementation attempts lookup and returns true if successful.
     * Implementations may override with more efficient checks.
     *
     * @param path The path to check
     * @return true if path can be read, false otherwise
     */
    suspend fun canRead(path: P): Boolean {
        return try {
            lookup(path)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if path is writable.
     *
     * Default implementation checks if parent directory is writable.
     * Implementations may override with more accurate checks.
     *
     * @param path The path to check
     * @return true if path can be written, false otherwise
     */
    suspend fun canWrite(path: P): Boolean {
        return try {
            exists(path) && lookup(path).let { true }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFileSystem(path: P): FileSystem
}