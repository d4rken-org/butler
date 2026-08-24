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
 * val extended = safOps.lookup(path, LookupOptions.EXTENDED)
 *
 * // Test code (no Android framework needed!)
 * val mockOps = MockFileSystemOps<SAFPath, SAFPathLookup>()
 * mockOps.addMockFile("/test.txt", "content".toByteArray())
 * val lookup = mockOps.lookup(testPath)
 * ```
 *
 * @param P The path type (LocalPath, SAFPath, etc.)
 * @param PL The path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 */
interface FileSystemOps<P : APath<P>, PL : APathLookup<P>> {

    /**
     * Look up metadata for a single path.
     *
     * Returns a lookup object containing file metadata. The amount of metadata fetched
     * depends on the [options] parameter:
     * - Basic metadata (fileType, size, modifiedAt) is always fetched
     * - Extended metadata (ownership, permissions, createdAt) is optional for performance
     *
     * Does not follow symlinks unless the implementation specifically documents that it does.
     *
     * @param path The path to look up
     * @param options Controls which metadata to fetch (default: basic only)
     * @return Path lookup with metadata
     * @throws eu.darken.butler.common.files.errors.ReadException if path cannot be read or doesn't exist
     */
    suspend fun lookup(path: P, options: LookupOptions): PL

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
     * @param options Controls which metadata to fetch for each child (default: basic only)
     * @return List of path lookups for all children (empty if directory is empty)
     * @throws eu.darken.butler.common.files.errors.ReadException if path cannot be read, doesn't exist, or is not a directory
     */
    suspend fun lookupFiles(path: P, options: LookupOptions): List<PL> {
        return listFiles(path).map { lookup(it, options) }
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
     * Create a directory.
     *
     * @param path The directory path to create
     * @param createParents If true, creates all missing parent directories (like `mkdir -p`).
     *                      If false, fails if parent directory doesn't exist (like `mkdir`).
     *                      Default: false (Unix semantics).
     * @throws eu.darken.butler.common.files.errors.PathAlreadyExistsException if path exists as a file
     * @throws eu.darken.butler.common.files.errors.WriteException if creation fails (e.g., parent doesn't exist when createParents=false)
     */
    suspend fun createDir(path: P, createParents: Boolean = false)

    /**
     * Create an empty file.
     *
     * Fails if file already exists (not idempotent like createDir).
     *
     * @param path The file path to create
     * @param createParents If true, creates all missing parent directories.
     *                      If false, fails if parent directory doesn't exist.
     *                      Default: false (Unix semantics).
     * @throws eu.darken.butler.common.files.errors.PathAlreadyExistsException if file already exists
     * @throws eu.darken.butler.common.files.errors.WriteException if creation fails (e.g., parent doesn't exist when createParents=false)
     */
    suspend fun createFile(path: P, createParents: Boolean = false)

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
     * Fully resolve symlinks and normalize a path to its authoritative real path.
     *
     * Filesystems without symlinks return the (normalized) path itself. Throws when the path cannot
     * be authoritatively resolved (e.g. it doesn't exist or a symlink target is missing/inaccessible)
     * so callers can treat failure as "unresolvable" rather than silently using a non-canonical path.
     *
     * @param path The path to resolve
     * @return The canonical real path
     * @throws eu.darken.butler.common.files.errors.ReadException if the path cannot be resolved
     */
    suspend fun canonicalize(path: P): P

    /**
     * Move/rename a file or directory atomically.
     *
     * Attempts an atomic move when source and destination are on the same file system.
     * Does NOT fall back to copy+delete — that is the caller's responsibility on [MoveOutcome.NotSupported].
     *
     * Contract:
     * - [MoveOutcome.Moved]: the document now exists at [destination] under the requested name.
     * - [MoveOutcome.NotSupported]: provably nothing was mutated; the caller may fall back to copy+delete.
     * - Exceptions signal failure, possibly with side effects — callers must NOT assume the source
     *   is still intact and must not blindly fall back to copying it.
     *
     * Note: SAF cannot atomically reparent AND rename in one step; such moves return [MoveOutcome.NotSupported].
     *
     * @param source The source path to move
     * @param destination The destination path
     * @throws eu.darken.butler.common.files.errors.WriteException if move fails
     */
    suspend fun move(source: P, destination: P): MoveOutcome

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
     * Creates the file if it doesn't exist yet (both append and truncate modes).
     * The parent directory must already exist — parents are NOT created.
     *
     * @param path The file path to write
     * @param append If true, append to existing file; if false, truncate existing content
     * @return OutputStream for writing
     * @throws eu.darken.butler.common.files.errors.WriteException if file cannot be opened or created
     */
    suspend fun openOutputStream(path: P, append: Boolean = false): OutputStream

    /**
     * Open a random-access handle on a file.
     *
     * Reads and writes are positioned, so no seeking is involved and any order of offsets is valid.
     * `size()` reflects the file as the handle sees it and stays stable while nothing writes through
     * the handle. The handle carries whatever lease its gateway needs (a root/ADB session, for
     * example) and stays valid until it is closed, which also releases that lease.
     *
     * Caller is responsible for closing the handle.
     *
     * @param path The file to open
     * @param readWrite If true, the handle may also write; if false, it is read-only
     * @throws eu.darken.butler.common.files.errors.ReadException if the file cannot be opened
     */
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
    suspend fun canRead(path: P): Boolean = try {
        lookup(path, LookupOptions())
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Check if path is writable.
     *
     * Semantics are implementation-defined and may require elevated privileges; typically true if
     * the path (or its parent, when the path does not yet exist) can be written to.
     *
     * @param path The path to check
     * @return true if path can be written, false otherwise
     */
    suspend fun canWrite(path: P): Boolean

    suspend fun getFileSystem(path: P): FileSystem
}