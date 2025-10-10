package eu.darken.butler.common.files.saf

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.provider.DocumentsContract
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.operations.FileSystemOps
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant

/**
 * FileSystemOps implementation for SAFPath using Android Storage Access Framework.
 *
 * This class encapsulates all Android-specific dependencies (Context, ContentResolver,
 * UriPermissions) making SAF operations testable without Android framework.
 *
 * ## Key Innovation: Testability
 *
 * By wrapping Context/ContentResolver/UriPermissions in this class, we enable:
 * - **Unit testing without Android**: Use MockSAFFileSystemOps instead
 * - **Fast tests**: No ContentProvider initialization or file system access
 * - **Controlled tests**: Full control over mock behavior (permissions, errors, etc.)
 *
 * ## How It Works
 *
 * All SAF logic that was scattered in SAFGateway is now centralized here:
 * 1. **Permission matching**: `findDocFile()` finds closest URI permission
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
 *     uriPermissionsProvider = { contentResolver.persistedUriPermissions }
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
 * @param uriPermissionsProvider Lambda providing current URI permissions (allows lazy evaluation)
 */
class SAFFileSystemOps(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val uriPermissionsProvider: () -> List<UriPermission>
) : FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended> {

    /**
     * Find DocumentFile for a SAFPath by matching against persisted URI permissions.
     *
     * This is the core of SAF access control - we need a URI permission that covers
     * the requested path. This method finds the closest permission and builds a
     * tree URI from it.
     *
     * Moved from SAFGateway.findDocFile()
     */
    private fun findDocFile(path: SAFPath): SAFDocFile {
        val permissions = uriPermissionsProvider()
        val match = path.findPermission(permissions)

        if (match == null) {
            log(TAG, VERBOSE) { "No UriPermission match for $path" }
            throw MissingUriPermissionException(path = path)
        }

        val targetTreeUri = SAFDocFile.buildTreeUri(
            match.permission.uri,
            match.missingSegments,
        )
        return SAFDocFile.fromTreeUri(context, contentResolver, targetTreeUri)
    }

    override suspend fun lookup(path: SAFPath): SAFPathLookup {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "lookup($path) -> $docFile" }

            if (!docFile.readable) {
                throw IOException("readable=false")
            }

            SAFPathLookup(
                lookedUp = path,
                docFile = docFile,
            )
        } catch (e: Exception) {
            log(TAG, WARN) { "lookup($path) failed." }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun lookupExtended(path: SAFPath): SAFPathLookupExtended {
        return try {
            val basicLookup = lookup(path)
            log(TAG, VERBOSE) { "lookupExtended($path)" }

            // SAFPathLookupExtended wraps basic lookup and lazily fetches fstat if available
            SAFPathLookupExtended(lookup = basicLookup)
        } catch (e: Exception) {
            log(TAG, WARN) { "lookupExtended($path) failed." }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun listFiles(path: SAFPath): List<SAFPath> {
        return try {
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
    }

    override suspend fun exists(path: SAFPath): Boolean {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "exists(): $path -> $docFile" }
            docFile.exists
        } catch (e: MissingUriPermissionException) {
            false
        } catch (e: Exception) {
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun delete(path: SAFPath): Boolean {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "delete(): $path -> $docFile" }
            docFile.delete()
        } catch (e: Exception) {
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun createDir(path: SAFPath) {
        try {
            log(TAG, VERBOSE) { "createDir(): $path" }
            val docFile = findDocFile(path)

            if (docFile.exists) {
                if (docFile.isDirectory) {
                    // Already exists - idempotent
                    return
                }
                throw WriteException("Path exists but is not a directory", path)
            }

            // Create directory (including parents if needed)
            createDocumentFile(DocumentsContract.Document.MIME_TYPE_DIR, path)
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
            createDocumentFile(FILE_TYPE_DEFAULT, path)
        } catch (e: Exception) {
            log(TAG, WARN) { "createFile($path) failed: ${e.asLog()}" }
            throw WriteException(path = path, cause = e)
        }
    }

    /**
     * Create a document file (file or directory) at the target path.
     * Creates parent directories as needed.
     *
     * Moved from SAFGateway.createDocumentFile()
     */
    private fun createDocumentFile(mimeType: String, targetSafPath: SAFPath): SAFDocFile {
        if (targetSafPath.segments.isEmpty()) {
            throw IllegalArgumentException("Can't create file/dir on treeRoot without segments!")
        }
        val targetName = targetSafPath.segments.last()

        // Ensure parent directories exist
        val targetParentDocFile: SAFDocFile = targetSafPath.segments
            .dropLast(1)
            .fold(targetSafPath.copy(segments = emptyList())) { currentPath, segment ->
                val segmentPath = currentPath.child(segment)
                val segmentDocFile = findDocFile(segmentPath)
                if (!segmentDocFile.exists) {
                    log(TAG, VERBOSE) { "Create parent folder $segmentPath" }
                    segmentDocFile.createDirectory(segment)
                }
                segmentPath
            }
            .let { findDocFile(it) }

        val existing = targetParentDocFile.findFile(targetName)
        check(existing == null) { "File already exists: ${existing?.uri}" }

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

    override suspend fun openInputStream(path: SAFPath): InputStream {
        return try {
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
    }

    override suspend fun openOutputStream(path: SAFPath, append: Boolean): OutputStream {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "openOutputStream(append=$append): $path -> $docFile" }

            if (!docFile.writable) {
                throw IOException("writable=false")
            }

            val mode = if (append) "wa" else "w"
            contentResolver.openOutputStream(docFile.uri, mode)
                ?: throw IOException("Couldn't open output stream for $path")
        } catch (e: Exception) {
            log(TAG, WARN) { "openOutputStream($path, append=$append) failed: ${e.asLog()}" }
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun setModifiedAt(path: SAFPath, modifiedAt: Instant): Boolean {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "setModifiedAt(): $path -> $docFile" }
            docFile.setLastModified(modifiedAt)
        } catch (e: Exception) {
            log(TAG, WARN) { "setModifiedAt($path, $modifiedAt) failed: ${e.asLog()}" }
            false
        }
    }

    override suspend fun setPermissions(path: SAFPath, permissions: Permissions): Boolean {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "setPermissions(): $path -> $docFile" }
            docFile.setPermissions(permissions)
        } catch (e: Exception) {
            log(TAG, WARN) { "setPermissions($path, $permissions) failed: ${e.asLog()}" }
            false
        }
    }

    override suspend fun setOwnership(path: SAFPath, ownership: Ownership): Boolean {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "setOwnership(): $path -> $docFile" }
            docFile.setOwnership(ownership)
        } catch (e: Exception) {
            log(TAG, WARN) { "setOwnership($path, $ownership) failed: ${e.asLog()}" }
            false
        }
    }

    override suspend fun createSymlink(linkPath: SAFPath, targetPath: SAFPath): Boolean {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun canRead(path: SAFPath): Boolean {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "canRead(): $path -> $docFile" }
            docFile.readable
        } catch (e: MissingUriPermissionException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun canWrite(path: SAFPath): Boolean {
        return try {
            val docFile = findDocFile(path)
            log(TAG, VERBOSE) { "canWrite(): $path -> $docFile" }
            docFile.writable
        } catch (e: MissingUriPermissionException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val TAG = logTag("FileSystemOps", "SAF")
        private const val FILE_TYPE_DEFAULT: String = "application/octet-stream"
    }
}
