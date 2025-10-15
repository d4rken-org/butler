package eu.darken.butler.common.files.saf

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import okio.FileHandle
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * FileSystemOps implementation for SAFPath using Android Storage Access Framework.
 *
 * This class encapsulates all Android-specific dependencies (Context, ContentResolver)
 * and uses SAFLocationManager for permission management, making SAF operations
 * testable without Android framework.
 *
 * ## Key Innovation: Testability
 *
 * By wrapping Context/ContentResolver in this class and delegating permission
 * management to SAFLocationManager, we enable:
 * - **Unit testing without Android**: Use MockSAFFileSystemOps instead
 * - **Fast tests**: No ContentProvider initialization or file system access
 * - **Controlled tests**: Full control over mock behavior (permissions, errors, etc.)
 *
 * ## How It Works
 *
 * All SAF logic that was scattered in SAFGateway is now centralized here:
 * 1. **Permission matching**: Delegates to `SAFLocationManager.findPermissionFor()`
 * 2. **Document operations**: Uses DocumentsContract for all file operations
 * 3. **Stream access**: ContentResolver for reading/writing file contents
 *
 * ## Usage
 *
 * ```kotlin
 * // Production code
 * val safOps = SAFFileSystemOps(
 *     context = context,
 *     contentResolver = contentResolver,
 *     locationManager = safLocationManager
 * )
 * val lookup = safOps.lookup(safPath)
 *
 * // Test code (no Android!)
 * val mockOps = MockSAFFileSystemOps()
 * mockOps.addMockDocumentFile(safPath, "text/plain", content)
 * val lookup = mockOps.lookup(safPath)
 * ```
 *
 * @param context Android context for SAFDocFile operations
 * @param contentResolver ContentResolver for document operations
 * @param locationManager SAFLocationManager for permission checking and management
 */
@Singleton
class SAFFileSystemOps @Inject constructor(
    private val contentResolver: ContentResolver,
    private val locationManager: SAFLocationManager
) : FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended> {

    private data class CacheEntry(
        val docFile: SAFDocFile,
        val cachedAt: Instant,
    )

    private val docFileCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<SAFPath, CacheEntry>(INITIAL_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SAFPath, CacheEntry>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    private data class LookupCacheEntry(
        val lookup: SAFPathLookup,
        val cachedAt: Instant,
    )

    private val lookupCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<SAFPath, LookupCacheEntry>(INITIAL_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SAFPath, LookupCacheEntry>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    // Attribute operation support cache (null = unknown, true = supported, false = not supported)
    @Volatile private var supportsSetModifiedAt: Boolean? = null
    @Volatile private var supportsSetPermissions: Boolean? = null
    @Volatile private var supportsSetOwnership: Boolean? = null

    private fun SAFPath.resolveDocFile(): SAFDocFile {
        val now = Clock.System.now()

        // Check cache first
        val cached = docFileCache[this]
        if (cached != null) {
            val age = now - cached.cachedAt
            if (age < CACHE_TTL) {
                if (Bugs.isTrace) log(TAG, VERBOSE) { "resolveDocFile() $this -> ${cached.docFile} (cached)" }
                return cached.docFile
            } else {
                // Expired entry
                docFileCache.remove(this)
            }
        }

        // Cache miss or expired - fetch fresh
        val docFile = locationManager.getDocFileFor(this)
        if (Bugs.isTrace) log(TAG, VERBOSE) { "resolveDocFile() $this -> $docFile" }

        if (docFile != null) {
            docFileCache[this] = CacheEntry(docFile, now)
        }

        return docFile ?: throw MissingUriPermissionException(path = this)
    }

    private fun SAFDocFile.performLookup(path: SAFPath): SAFPathLookup {
        if (!readable) throw IOException("readable=false")
        val data = getLookupData()
        return SAFPathLookup(
            lookedUp = path,
            fileType = data.fileType,
            size = data.size,
            modifiedAt = data.lastModified,
        )
    }

    override suspend fun lookup(path: SAFPath): SAFPathLookup {
        return try {
            val now = Clock.System.now()

            // Check lookup cache first
            val cached = lookupCache[path]
            if (cached != null) {
                val age = now - cached.cachedAt
                if (age < CACHE_TTL) {
                    if (Bugs.isTrace) log(TAG, VERBOSE) { "lookup() $path -> ${cached.lookup} (cached)" }
                    return cached.lookup
                } else {
                    // Expired entry
                    lookupCache.remove(path)
                }
            }

            // Cache miss or expired - perform lookup
            val lookup = path.resolveDocFile().performLookup(path)

            // Cache the result
            lookupCache[path] = LookupCacheEntry(lookup, now)

            lookup
        } catch (e: Exception) {
            log(TAG, WARN) { "lookup($path) failed." }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun lookupExtended(path: SAFPath): SAFPathLookupExtended = try {
        log(TAG, VERBOSE) { "lookupExtended($path)" }
        val now = Clock.System.now()

        // Resolve docFile once and reuse for both operations
        val docFile = path.resolveDocFile()

        // Check lookup cache for basic metadata
        val cached = lookupCache[path]
        val lookup = if (cached != null && (now - cached.cachedAt) < CACHE_TTL) {
            // Cache hit - use cached lookup
            if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupExtended() using cached lookup for $path" }
            cached.lookup
        } else {
            // Cache miss or expired - perform lookup and cache result
            if (cached != null) {
                lookupCache.remove(path)
            }
            val newLookup = docFile.performLookup(path)
            lookupCache[path] = LookupCacheEntry(newLookup, now)
            newLookup
        }

        // Query extended attributes using same docFile
        val fstat = if (supportsSetOwnership != false || supportsSetPermissions != false) {
            docFile.fstat()
        } else {
            null
        }

        SAFPathLookupExtended(
            lookup = lookup,
            ownership = fstat?.let { Ownership(it.st_uid.toLong(), it.st_gid.toLong()) },
            permissions = fstat?.let { Permissions(it.st_mode) },
            createdAt = null,
        )
    } catch (e: Exception) {
        log(TAG, WARN) { "lookupExtended($path) failed." }
        throw ReadException(path = path, cause = e)
    }

    override suspend fun listFiles(path: SAFPath): List<SAFPath> = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "listFiles($path) -> $docFile" }

        val now = Clock.System.now()

        // Use batch query to get files + metadata in one query
        val filesWithMetadata = docFile.listFilesWithLookupData()

        // Map to SAFPath and populate lookup cache
        filesWithMetadata.map { (file, lookupData) ->
            val name = file.name ?: file.uri.pathSegments.last().split('/').last()
            val childPath = path.child(name)

            // Populate lookup cache with batch-queried metadata
            val lookup = SAFPathLookup(
                lookedUp = childPath,
                fileType = lookupData.fileType,
                size = lookupData.size,
                modifiedAt = lookupData.lastModified,
            )
            lookupCache[childPath] = LookupCacheEntry(lookup, now)

            childPath
        }
    } catch (e: Exception) {
        log(TAG, WARN) { "listFiles($path) failed." }
        throw ReadException(path = path, cause = e)
    }

    override suspend fun exists(path: SAFPath): Boolean = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "exists(): $path -> $docFile" }
        docFile.exists
    } catch (e: Exception) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun delete(path: SAFPath, recursive: Boolean): Boolean {
        return try {
            log(TAG, VERBOSE) { "delete(recursive=$recursive): $path" }
            val docFile = path.resolveDocFile()

            if (!docFile.exists) {
                return false
            }

            // If recursive and it's a directory, delete children first (post-order)
            if (recursive && docFile.isDirectory) {
                val children = listFiles(path)
                children.forEach { childPath ->
                    delete(childPath, recursive = true)
                }
            }

            // Delete the path itself
            docFile.delete()
        } catch (e: Exception) {
            throw WriteException(path = path, cause = e)
        }
    }

    private fun createDocumentFile(mimeType: String, targetSafPath: SAFPath): SAFDocFile {
        if (targetSafPath.segments.isEmpty()) {
            throw IllegalArgumentException("Can't create file/dir on treeRoot without segments!")
        }
        val targetName = targetSafPath.segments.last()

        // Get parent path - must already exist (mkdir semantics, not mkdirs)
        val parentPath = if (targetSafPath.segments.size > 1) {
            targetSafPath.copy(segments = targetSafPath.segments.dropLast(1))
        } else {
            targetSafPath.copy(segments = emptyList())
        }

        val targetParentDocFile = parentPath.resolveDocFile()
        if (!targetParentDocFile.exists) {
            throw WriteException("Parent directory does not exist: $parentPath", targetSafPath)
        }
        if (!targetParentDocFile.isDirectory) {
            throw WriteException("Parent is not a directory: $parentPath", targetSafPath)
        }

        val targetDocFile = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            targetParentDocFile.createDirectory(targetName)
        } else {
            targetParentDocFile.createFile(mimeType, targetName)
        }
        require(targetName == targetDocFile.name) {
            "Unexpected name change: Wanted $targetName, but got ${targetDocFile.name}"
        }

        log(TAG, VERBOSE) { "createDocumentFile(mimeType=$mimeType, targetSafPath=$targetSafPath" }
        return targetDocFile
    }

    /**
     * Ensures that the parent directory of the given path exists.
     *
     * If createParents is true, recursively creates any missing parent directories.
     * If createParents is false, throws WriteException if the parent doesn't exist.
     *
     * @param path The path whose parent should exist
     * @param createParents Whether to create missing parent directories
     * @throws WriteException if parent doesn't exist and createParents is false,
     *         or if parent exists but is not a directory
     */
    private suspend fun ensureParentExists(path: SAFPath, createParents: Boolean) {
        // Base case: if path has no segments, it's the tree root which always exists
        if (path.segments.isEmpty()) return

        val parentPath = path.parent ?: return // No parent (tree root)

        // If parent is tree root (no segments), it always exists
        if (parentPath.segments.isEmpty()) return

        // Try to resolve the parent DocFile
        val parentDocFile = try {
            parentPath.resolveDocFile()
        } catch (e: MissingUriPermissionException) {
            // Permission issue - can't proceed
            throw e
        } catch (e: Exception) {
            // Parent might not exist
            if (!createParents) {
                throw WriteException("Parent directory does not exist: $parentPath", path, e)
            }

            // Recursively ensure the parent's parent exists
            ensureParentExists(parentPath, createParents = true)

            // Now create this missing parent directory
            createMissingParentDir(parentPath)
            return
        }

        // Parent DocFile resolved - check if it exists
        if (!parentDocFile.exists) {
            if (!createParents) {
                throw WriteException("Parent directory does not exist: $parentPath", path)
            }

            // Recursively ensure the parent's parent exists
            ensureParentExists(parentPath, createParents = true)

            // Now create this missing parent directory
            createMissingParentDir(parentPath)
        } else if (!parentDocFile.isDirectory) {
            // Parent exists but is not a directory - this is an error
            throw WriteException("Parent exists but is not a directory: $parentPath", path)
        }
        // Otherwise parent exists and is a directory - we're done
    }

    /**
     * Creates a missing parent directory and updates the caches.
     * Assumes the parent's parent already exists.
     */
    private suspend fun createMissingParentDir(parentPath: SAFPath) {
        require(parentPath.segments.isNotEmpty()) { "Cannot create tree root" }

        val parentName = parentPath.segments.last()
        val grandParentPath = if (parentPath.segments.size > 1) {
            parentPath.copy(segments = parentPath.segments.dropLast(1))
        } else {
            parentPath.copy(segments = emptyList())
        }

        val grandParentDocFile = grandParentPath.resolveDocFile()

        if (!grandParentDocFile.exists || !grandParentDocFile.isDirectory) {
            throw WriteException(
                "Grandparent directory does not exist or is not a directory: $grandParentPath",
                parentPath
            )
        }

        val newParentDocFile = grandParentDocFile.createDirectory(parentName)

        log(TAG, VERBOSE) { "createMissingParentDir() created: $parentPath" }

        // Update caches
        val now = Clock.System.now()
        docFileCache[parentPath] = CacheEntry(newParentDocFile, now)

        // Also cache lookup data
        val lookup = newParentDocFile.performLookup(parentPath)
        lookupCache[parentPath] = LookupCacheEntry(lookup, now)
    }

    override suspend fun createDir(path: SAFPath, createParents: Boolean) {
        try {
            log(TAG, VERBOSE) { "createDir(createParents=$createParents): $path" }

            ensureParentExists(path, createParents)

            val docFile = path.resolveDocFile()

            if (docFile.exists) {
                if (docFile.isDirectory)                     return // Already exists - idempotent

                    throw PathAlreadyExistsException(
                        message = "Path exists but is not a directory",
                        path = path
                    )
            }

            createDocumentFile(DocumentsContract.Document.MIME_TYPE_DIR, path)
        } catch (e: PathAlreadyExistsException) {
            throw e // Re-throw PathAlreadyExistsException as-is
        } catch (e: Exception) {
            log(TAG, WARN) { "createDir($path) failed: ${e.asLog()}" }
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun createFile(path: SAFPath, createParents: Boolean) {
        try {
            log(TAG, VERBOSE) { "createFile(createParents=$createParents): $path" }

            ensureParentExists(path, createParents)

            val docFile = path.resolveDocFile()
            if (docFile.exists)                 throw PathAlreadyExistsException(path = path)

            createDocumentFile("application/octet-stream", path)
        } catch (e: PathAlreadyExistsException) {
            throw e // Re-throw PathAlreadyExistsException as-is
        } catch (e: Exception) {
            log(TAG, WARN) { "createFile($path) failed: ${e.asLog()}" }
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun openInputStream(path: SAFPath): InputStream = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "openInputStream(): $path -> $docFile" }

        if (!docFile.readable) {
            throw IOException("readable=false")
        }

        contentResolver.openInputStream(docFile.uri)
            ?: throw IOException("Couldn't open input stream for $path")
    } catch (e: Exception) {
        log(TAG, WARN) { "openInputStream($path) failed: ${e.asLog()}" }
        throw ReadException(path = path, cause = e)
    }

    override suspend fun openOutputStream(path: SAFPath, append: Boolean): OutputStream = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "openOutputStream(append=$append): $path -> $docFile" }

        if (!docFile.writable) throw IOException("writable=false")

        val mode = if (append) "wa" else "w"
        contentResolver.openOutputStream(docFile.uri, mode)
            ?: throw IOException("Couldn't open output stream for $path")
    } catch (e: Exception) {
        log(TAG, WARN) { "openOutputStream($path, append=$append) failed: ${e.asLog()}" }
        throw WriteException(path = path, cause = e)
    }

    override suspend fun file(path: SAFPath, readWrite: Boolean): FileHandle {
        return try {
            log(TAG, VERBOSE) { "file(readWrite=$readWrite): $path" }

            if (readWrite && !canWrite(path)) throw IOException("writable=false")
            else if (!canRead(path)) throw IOException("readable=false")

            val pfd = openPFD(path, if (readWrite) FileMode.READ_WRITE else FileMode.READ)
            pfd.toFileHandle(readWrite)
        } catch (e: Exception) {
            log(TAG, WARN) { "file($path, readWrite=$readWrite) failed: ${e.asLog()}" }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun setModifiedAt(path: SAFPath, modifiedAt: Instant): Boolean {
        // Check cache - skip if known to be unsupported
        if (supportsSetModifiedAt == false) {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "setModifiedAt() skipped (cached as unsupported)" }
            return false
        }

        return try {
            val docFile = path.resolveDocFile()
            log(TAG, VERBOSE) { "setModifiedAt(): $path -> $docFile" }
            val success = docFile.setLastModified(modifiedAt)

            // Update cache on first attempt
            if (supportsSetModifiedAt == null) {
                supportsSetModifiedAt = success
                if (!success) {
                    log(TAG, INFO) { "setModifiedAt() not supported by this SAF provider (cached)" }
                }
            }

            success
        } catch (e: Exception) {
            log(TAG, WARN) { "setModifiedAt($path, $modifiedAt) failed: $e" }
            supportsSetModifiedAt = false
            false
        }
    }

    override suspend fun setPermissions(path: SAFPath, permissions: Permissions): Boolean {
        // Check cache - skip if known to be unsupported
        if (supportsSetPermissions == false) {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "setPermissions() skipped (cached as unsupported)" }
            return false
        }

        return try {
            val docFile = path.resolveDocFile()
            log(TAG, VERBOSE) { "setPermissions(): $path -> $docFile" }
            val success = docFile.setPermissions(permissions)

            // Update cache on first attempt
            if (supportsSetPermissions == null) {
                supportsSetPermissions = success
                if (!success) {
                    log(TAG, INFO) { "setPermissions() not supported by this file system (cached)" }
                }
            }

            success
        } catch (e: Exception) {
            log(TAG, WARN) { "setPermissions($path, $permissions) failed: ${e.asLog()}" }
            supportsSetPermissions = false
            false
        }
    }

    override suspend fun setOwnership(path: SAFPath, ownership: Ownership): Boolean {
        // Check cache - skip if known to be unsupported
        if (supportsSetOwnership == false) {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "setOwnership() skipped (cached as unsupported)" }
            return false
        }

        return try {
            val docFile = path.resolveDocFile()
            log(TAG, VERBOSE) { "setOwnership(): $path -> $docFile" }
            val success = docFile.setOwnership(ownership)

            // Update cache on first attempt
            if (supportsSetOwnership == null) {
                supportsSetOwnership = success
                if (!success) {
                    log(TAG, INFO) { "setOwnership() not supported (requires root privileges, cached)" }
                }
            }

            success
        } catch (e: Exception) {
            log(TAG, WARN) { "setOwnership($path, $ownership) failed: ${e.asLog()}" }
            supportsSetOwnership = false
            false
        }
    }

    override suspend fun createSymlink(linkPath: SAFPath, targetPath: SAFPath): Boolean {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun readSymbolicLink(linkPath: SAFPath): SAFPath {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun move(source: SAFPath, destination: SAFPath): Boolean = try {
        log(TAG, VERBOSE) { "move(): $source -> $destination" }

        val sourceDocFile = source.resolveDocFile()

        if (!sourceDocFile.exists) {
            throw ReadException("Source does not exist", source)
        }

        // Get source parent directory
        val sourceParentPath = if (source.segments.size > 1) {
            source.copy(segments = source.segments.dropLast(1))
        } else {
            source.copy(segments = emptyList())
        }
        val sourceParentDocFile = sourceParentPath.resolveDocFile()

        // Get destination parent directory
        val destParentPath = if (destination.segments.size > 1) {
            destination.copy(segments = destination.segments.dropLast(1))
        } else {
            destination.copy(segments = emptyList())
        }
        val destParentDocFile = destParentPath.resolveDocFile()

        if (!destParentDocFile.exists || !destParentDocFile.isDirectory) {
            throw WriteException("Destination parent does not exist or is not a directory", destination)
        }

        // Use DocumentsContract.moveDocument (requires API 24+)
        val movedUri = DocumentsContract.moveDocument(
            contentResolver,
            sourceDocFile.uri,
            sourceParentDocFile.uri,
            destParentDocFile.uri
        )

        val success = movedUri != null

        if (success) {
            // Invalidate cache entries for both source and destination
            docFileCache.remove(source)
            docFileCache.remove(destination)
            lookupCache.remove(source)
            lookupCache.remove(destination)
        }

        success
    } catch (e: Exception) {
        log(TAG, WARN) { "move($source, $destination) failed: ${e.asLog()}" }
        throw WriteException(path = source, cause = e)
    }

    override suspend fun canRead(path: SAFPath): Boolean = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "canRead(): $path -> $docFile" }
        docFile.readable
    } catch (e: Exception) {
        log(TAG, WARN) { "canRead($path): $e" }
        false
    }

    override suspend fun canWrite(path: SAFPath): Boolean = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "canWrite(): $path -> $docFile" }
        docFile.writable
    } catch (e: Exception) {
        log(TAG, WARN) { "canWrite($path): $e" }
        false
    }

    fun openPFD(path: SAFPath, mode: FileMode): ParcelFileDescriptor {
        return path.resolveDocFile().openPFD(mode)
    }

    override suspend fun getFileSystem(path: SAFPath): FileSystem {
        val statvfs = try {
            log(TAG, VERBOSE) { "getFileSystem(): $path" }

            val pfd = openPFD(path, FileMode.READ)
            pfd.use { android.system.Os.fstatvfs(it.fileDescriptor) }
        } catch (e: Exception) {
            log(TAG, WARN) { "getFileSystem($path) failed: ${e.asLog()}" }
            null
        }

        return FileSystem(
            freeSpace = statvfs?.let { it.f_bavail * it.f_frsize },
            totalSpace = statvfs?.let { it.f_blocks * it.f_frsize },
        )
    }

    enum class FileMode(val value: String) {
        READ_WRITE("rw"), WRITE("w"), READ("r")
    }

    companion object {
        private val TAG = logTag("Gateway", "SAF", "FileSystemOps")

        private const val INITIAL_CACHE_SIZE = 16
        private const val MAX_CACHE_SIZE = 1000
        private val CACHE_TTL = 10.seconds
    }
}
