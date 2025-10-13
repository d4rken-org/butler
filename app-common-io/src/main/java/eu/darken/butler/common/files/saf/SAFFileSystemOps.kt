package eu.darken.butler.common.files.saf

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.FileSystemOps
import eu.darken.butler.common.files.saf.location.SAFLocationManager
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

    private fun findDocFile(path: SAFPath): SAFDocFile {
        val now = Clock.System.now()

        // Check cache first
        val cached = docFileCache[path]
        if (cached != null) {
            val age = now - cached.cachedAt
            if (age < CACHE_TTL) {
                if (Bugs.isTrace) log(TAG, VERBOSE) { "findDocFile() $path -> $cached.docFile (cached)" }
                return cached.docFile
            } else {
                // Expired entry
                docFileCache.remove(path)
            }
        }

        // Cache miss or expired - fetch fresh
        val docFile = locationManager.getDocFileFor(path)
        if (Bugs.isTrace) log(TAG, VERBOSE) { "findDocFile() $path -> $docFile" }

        if (docFile != null) {
            docFileCache[path] = CacheEntry(docFile, now)
        }

        return docFile ?: throw MissingUriPermissionException(path = path)
    }

    private fun SAFPath.performLookup(): Pair<SAFDocFile, SAFPathLookup> {
        val docFile = findDocFile(this)

        if (!docFile.readable) throw IOException("readable=false")

        return docFile to SAFPathLookup(
            lookedUp = this,
            fileType = when {
                docFile.isDirectory -> FileType.DIRECTORY
                else -> FileType.FILE
            },
            size = docFile.length,
            modifiedAt = docFile.lastModified,
        )
    }

    override suspend fun lookup(path: SAFPath): SAFPathLookup = try {
        path.performLookup().second
    } catch (e: Exception) {
        log(TAG, WARN) { "lookup($path) failed." }
        throw ReadException(path = path, cause = e)
    }

    override suspend fun lookupExtended(path: SAFPath): SAFPathLookupExtended = try {
        log(TAG, VERBOSE) { "lookupExtended($path)" }
        val (docFile, lookup) = path.performLookup()

        val fstat = docFile.fstat()

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
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "listFiles($path) -> $docFile" }

        docFile.listFiles().map {
            val name = it.name ?: it.uri.pathSegments.last().split('/').last()
            path.child(name)
        }
    } catch (e: Exception) {
        log(TAG, WARN) { "listFiles($path) failed." }
        throw ReadException(path = path, cause = e)
    }

    override suspend fun exists(path: SAFPath): Boolean = try {
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "exists(): $path -> $docFile" }
        docFile.exists
    } catch (e: Exception) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun delete(path: SAFPath): Boolean = try {
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "delete(): $path -> $docFile" }
        docFile.delete()
    } catch (e: Exception) {
        throw WriteException(path = path, cause = e)
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

        val targetParentDocFile = findDocFile(parentPath)
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

    override suspend fun createDir(path: SAFPath) {
        try {
            log(TAG, VERBOSE) { "createDir(): $path" }
            val docFile = findDocFile(path)

            if (docFile.exists) {
                if (docFile.isDirectory) {
                    return // Already exists - idempotent
                } else {
                    throw WriteException("Path exists but is not a directory", path)
                }
            } else {
                // Create directory (parent must already exist)
                createDocumentFile(DocumentsContract.Document.MIME_TYPE_DIR, path)
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "createDir($path) failed: ${e.asLog()}" }
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun createFile(path: SAFPath) {
        try {
            log(TAG, VERBOSE) { "createFile(): $path" }
            val docFile = findDocFile(path)

            if (docFile.exists) {
                throw WriteException("File already exists", path)
            }

            // Create file with default MIME type
            createDocumentFile("application/octet-stream", path)
        } catch (e: Exception) {
            log(TAG, WARN) { "createFile($path) failed: ${e.asLog()}" }
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun openInputStream(path: SAFPath): InputStream = try {
        val docFile = findDocFile(path)
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
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "openOutputStream(append=$append): $path -> $docFile" }

        if (!docFile.writable) throw IOException("writable=false")

        val mode = if (append) "wa" else "w"
        contentResolver.openOutputStream(docFile.uri, mode)
            ?: throw IOException("Couldn't open output stream for $path")
    } catch (e: Exception) {
        log(TAG, WARN) { "openOutputStream($path, append=$append) failed: ${e.asLog()}" }
        throw WriteException(path = path, cause = e)
    }

    override suspend fun setModifiedAt(path: SAFPath, modifiedAt: Instant): Boolean = try {
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "setModifiedAt(): $path -> $docFile" }
        docFile.setLastModified(modifiedAt)
    } catch (e: Exception) {
        log(TAG, WARN) { "setModifiedAt($path, $modifiedAt) failed: $e" }
        false
    }

    override suspend fun setPermissions(path: SAFPath, permissions: Permissions): Boolean = try {
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "setPermissions(): $path -> $docFile" }
        docFile.setPermissions(permissions)
    } catch (e: Exception) {
        log(TAG, WARN) { "setPermissions($path, $permissions) failed: ${e.asLog()}" }
        false
    }

    override suspend fun setOwnership(path: SAFPath, ownership: Ownership): Boolean = try {
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "setOwnership(): $path -> $docFile" }
        docFile.setOwnership(ownership)
    } catch (e: Exception) {
        log(TAG, WARN) { "setOwnership($path, $ownership) failed: ${e.asLog()}" }
        false
    }

    override suspend fun createSymlink(linkPath: SAFPath, targetPath: SAFPath): Boolean {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun canRead(path: SAFPath): Boolean = try {
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "canRead(): $path -> $docFile" }
        docFile.readable
    } catch (e: Exception) {
        log(TAG, WARN) { "canRead($path): $e" }
        false
    }

    override suspend fun canWrite(path: SAFPath): Boolean = try {
        val docFile = findDocFile(path)
        log(TAG, VERBOSE) { "canWrite(): $path -> $docFile" }
        docFile.writable
    } catch (e: Exception) {
        log(TAG, WARN) { "canWrite($path): $e" }
        false
    }

    fun openPFD(path: SAFPath, mode: FileMode): ParcelFileDescriptor {
        return findDocFile(path).openPFD(mode)
    }

    enum class FileMode(val value: String) {
        READ_WRITE("rw"), WRITE("w"), READ("r")
    }

    companion object {
        private val TAG = logTag("SAF", "FileSystemOps")

        private const val INITIAL_CACHE_SIZE = 16
        private const val MAX_CACHE_SIZE = 1000
        private val CACHE_TTL = 10.seconds
    }
}
