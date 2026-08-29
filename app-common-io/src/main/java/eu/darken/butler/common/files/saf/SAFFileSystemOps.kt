package eu.darken.butler.common.files.saf

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okio.FileHandle
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
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
) : FileSystemOps<SAFPath, SAFPathLookup> {

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

    /**
     * Cache the provider-returned handle for a just-created/just-moved document.
     * Predicted URIs can be wrong on providers with opaque document IDs; the returned URI is authoritative.
     */
    private fun cacheCreated(path: SAFPath, docFile: SAFDocFile) {
        docFileCache[path] = CacheEntry(docFile, Clock.System.now())
        lookupCache.remove(path)
    }

    private fun invalidatePath(path: SAFPath) {
        docFileCache.remove(path)
        lookupCache.remove(path)
    }

    /** Invalidate a path and all cached descendants (stale after directory move/delete). */
    private fun invalidateSubtree(path: SAFPath) {
        invalidatePath(path)
        val isDescendant = { candidate: SAFPath ->
            candidate.treeRootUri == path.treeRootUri &&
                candidate.segments.size > path.segments.size &&
                candidate.segments.subList(0, path.segments.size) == path.segments
        }
        synchronized(docFileCache) { docFileCache.keys.removeAll { isDescendant(it) } }
        synchronized(lookupCache) { lookupCache.keys.removeAll { isDescendant(it) } }
    }

    /** Drop the cached lookup of a path's parent (child count/mtime changed). */
    private fun invalidateParentLookup(path: SAFPath) {
        path.parent?.let { lookupCache.remove(it) }
    }

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

    private fun SAFDocFile.performLookup(path: SAFPath, options: LookupOptions): SAFPathLookup {
        // Note: We don't pre-check readable here because ContentResolver.query() validates tree permissions internally.
        // If getLookupData() succeeds, the document is readable. If not, it throws SecurityException.
        val data = getLookupData()

        // SAF extended metadata (ownership/permissions/createdAt)
        // Note: SAF doesn't support Unix ownership/permissions, and has limited extended metadata support
        var ownership: Ownership? = null
        var permissions: Permissions? = null

        // Try to get fstat data if extended metadata requested (usually returns null for SAF)
        if (options.fetchOwnership || options.fetchPermissions) {
            val fstat = if (supportsSetOwnership != false || supportsSetPermissions != false) {
                fstat()
            } else {
                null
            }

            if (fstat != null) {
                if (options.fetchOwnership) {
                    ownership = Ownership(fstat.st_uid.toLong(), fstat.st_gid.toLong())
                }
                if (options.fetchPermissions) {
                    permissions = Permissions(fstat.st_mode)
                }
            }
        }

        return SAFPathLookup(
            lookedUp = path,
            fileType = data.fileType,
            size = data.size,
            modifiedAt = data.lastModified,
            ownership = ownership,
            permissions = permissions,
            createdAt = null, // SAF doesn't support creation time
        )
    }

    override suspend fun lookup(path: SAFPath, options: LookupOptions): SAFPathLookup {
        return try {
            val now = Clock.System.now()

            val isCatchWorthy = options.fetchSize && options.fetchModifiedAt

            // Check lookup cache first (only for basic lookups to avoid caching stale extended data)
            if (isCatchWorthy) {
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
            }

            // Cache miss or expired or extended lookup - perform lookup
            val lookup = path.resolveDocFile().performLookup(path, options)

            // Cache only basic lookups
            if (isCatchWorthy) {
                lookupCache[path] = LookupCacheEntry(lookup, now)
            }

            lookup
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "lookup($path, $options) failed." }

            // If fallbackToUnknown is true, return synthetic lookup instead of throwing
            if (options.fallbackToUnknown) {
                log(TAG, VERBOSE) { "Returning UNKNOWN lookup for non-existent path: $path" }
                return SAFPathLookup(
                    lookedUp = path,
                    fileType = FileType.UNKNOWN,
                    size = null,
                    modifiedAt = null,
                    target = null,
                    error = e.message,
                    ownership = null,
                    permissions = null,
                    createdAt = null,
                )
            }

            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun listFiles(path: SAFPath): List<SAFPath> = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "listFiles($path) -> $docFile" }

        val now = Clock.System.now()

        // Use batch query to get files + metadata in one query
        val filesWithMetadata = docFile.listFilesWithLookupData()

        // Map to SAFPath and populate lookup cache
        filesWithMetadata.mapIndexed { index, (file, lookupData) ->
            if (index % 50 == 0) currentCoroutineContext().ensureActive()

            val name = file.name ?: file.uri.pathSegments.last().split('/').last()
            val childPath = path.child(name)

            // Populate lookup cache with batch-queried metadata (basic only - no extended)
            val lookup = SAFPathLookup(
                lookedUp = childPath,
                fileType = lookupData.fileType,
                size = lookupData.size,
                modifiedAt = lookupData.lastModified,
                ownership = null, // Not available in batch listing
                permissions = null, // Not available in batch listing
                createdAt = null, // SAF doesn't support creation time
            )
            lookupCache[childPath] = LookupCacheEntry(lookup, now)

            childPath
        }
    } catch (e: CancellationException) {
        throw e
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

    /**
     * Uses [SAFDocFile.existsStrict], which addresses the provider through a client: no client means
     * nobody was asked, while [SAFDocFile.exists] cannot tell that from a document that is gone.
     */
    override suspend fun existsStrict(path: SAFPath): Existence = try {
        val docFile = path.resolveDocFile()
        if (docFile.existsStrict()) Existence.PRESENT else Existence.ABSENT
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "existsStrict($path) could not be answered: ${e.asLog()}" }
        Existence.UNKNOWN
    }

    override suspend fun delete(path: SAFPath, recursive: Boolean): Boolean {
        return try {
            log(TAG, VERBOSE) { "delete(recursive=$recursive): $path" }
            val docFile = path.resolveDocFile()

            if (!docFile.exists) {
                return false
            }

            try {
                deleteDocument(docFile, path, recursive)
            } finally {
                // In a finally, not only on success: a walk that was cancelled or that failed partway
                // has already deleted some descendants, and leaving those cached would serve stale
                // entries for up to CACHE_TTL. Pure in-memory map work, so it still runs under
                // cancellation.
                invalidateSubtree(path)
                invalidateParentLookup(path)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: WriteException) {
            throw e
        } catch (e: Exception) {
            throw WriteException(path = path, cause = e)
        }
    }

    /**
     * Post-order delete of [docFile], recursing over the documents the provider itself handed out.
     *
     * Going back through [SAFPath] would rebuild each child's document id from its display name.
     * Document ids are opaque in general, and a provider whose ids aren't path-derived is exactly the
     * kind this has to work against, so the walk stays on [SAFDocFile]. The path is carried along only
     * for log and exception context.
     */
    private suspend fun deleteDocument(docFile: SAFDocFile, path: SAFPath, recursive: Boolean) {
        if (recursive && docFile.isDirectory) {
            docFile.listFiles().forEach { child ->
                // Without a checkpoint per document, a cancelled recursive delete keeps deleting until
                // the enclosing withContext notices, which is only at the very end.
                currentCoroutineContext().ensureActive()
                val childName = child.name
                val childPath = if (childName != null) path.child(childName) else path
                log(TAG, VERBOSE) { "delete(): Descending into $childPath ($child)" }
                deleteDocument(child, childPath, recursive = true)
            }
        }

        currentCoroutineContext().ensureActive()
        if (docFile.delete()) return

        // Providers also report a failure for a document that is already gone, so a false here is
        // ambiguous on its own. Only a query that actually answers may turn it into a success -
        // existsStrict() raises rather than guessing when no provider answered, which is the whole
        // reason it exists. Returning false instead would tell the caller "it wasn't there", and
        // FileSystemOps.delete promises a WriteException for a delete that failed.
        if (!docFile.existsStrict()) {
            log(TAG, WARN) { "delete(): Already gone: $path ($docFile)" }
            return
        }

        throw WriteException("Delete was refused and the document is still there", path)
    }

    private suspend fun createDocumentFile(mimeType: String, targetSafPath: SAFPath): SAFDocFile {
        if (targetSafPath.segments.isEmpty()) {
            throw IllegalArgumentException("Can't create file/dir on treeRoot without segments!")
        }
        val targetName = targetSafPath.segments.last()

        // Parent must already exist (mkdir semantics, not mkdirs)
        val parentPath = targetSafPath.parent!!
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

        return try {
            targetDocFile.withVerifiedName(targetName).also {
                log(TAG, VERBOSE) { "createDocumentFile(mimeType=$mimeType, targetSafPath=$targetSafPath)" }
            }
        } catch (e: NameVerificationException) {
            // The mismatched document exists — clean it up rather than orphaning it, and name
            // what the provider produced so the user can recover if the cleanup fails too.
            val cleanedUp = try {
                e.doc.delete()
            } catch (inner: Exception) {
                false
            }
            throw WriteException(
                "Provider created '${e.actualName}' instead of '$targetName' " +
                    "(uri=${e.doc.uri}, cleanedUp=$cleanedUp)",
                targetSafPath,
                e,
            )
        }
    }

    /**
     * Verify this (created/moved) document landed under [expectedName]; providers may munge names
     * (sanitize characters, auto-suffix collisions). Attempts one corrective rename.
     *
     * @return the verified document (this, or the corrected one)
     * @throws WriteException if the name is unknown after retries — never rename/delete on guesses
     * @throws NameVerificationException on a confirmed, uncorrectable mismatch
     */
    private suspend fun SAFDocFile.withVerifiedName(expectedName: String): SAFDocFile {
        val actualName = awaitName()
        if (actualName == expectedName) return this
        if (actualName == null) {
            throw WriteException("Document has no queryable name (uri=$uri)")
        }

        val corrected = try {
            renameTo(expectedName)
        } catch (e: Exception) {
            log(TAG, WARN) { "Corrective rename to '$expectedName' failed: ${e.asLog()}" }
            null
        }
        if (corrected != null && corrected.awaitName() == expectedName) {
            log(TAG, VERBOSE) { "Corrected provider name '$actualName' -> '$expectedName'" }
            return corrected
        }

        throw NameVerificationException(actualName, corrected ?: this)
    }

    private class NameVerificationException(
        val actualName: String,
        val doc: SAFDocFile,
    ) : IOException("Document landed as '$actualName' (uri=${doc.uri})")

    /** Query the display name via the provider-returned URI, retrying briefly for slow providers. */
    private suspend fun SAFDocFile.awaitName(maxAttempts: Int = 3): String? {
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) delay(50.milliseconds)
            nameStrict()?.let { return it }
        }
        return null
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

        // Same normalized create path as regular creation (name-munging handling included)
        val newParentDocFile = createDocumentFile(DocumentsContract.Document.MIME_TYPE_DIR, parentPath)

        log(TAG, VERBOSE) { "createMissingParentDir() created: $parentPath" }

        cacheCreated(parentPath, newParentDocFile)

        // Also cache lookup data (basic only for newly created directory)
        val lookup = newParentDocFile.performLookup(parentPath, LookupOptions.BASE)
        lookupCache[parentPath] = LookupCacheEntry(lookup, Clock.System.now())
    }

    override suspend fun createDir(path: SAFPath, createParents: Boolean) {
        try {
            log(TAG, VERBOSE) { "createDir(createParents=$createParents): $path" }

            ensureParentExists(path, createParents)

            val docFile = path.resolveDocFile()

            if (docFile.existsStrict()) {
                if (docFile.isDirectory) return // Already exists - idempotent

                throw PathAlreadyExistsException(
                    message = "Path exists but is not a directory",
                    path = path
                )
            }

            val created = createDocumentFile(DocumentsContract.Document.MIME_TYPE_DIR, path)
            cacheCreated(path, created)
            invalidateParentLookup(path)

            // Wait for DocumentsProvider to make directory queryable (race condition fix)
            waitUntilQueryable(path)
        } catch (e: PathAlreadyExistsException) {
            throw e // Re-throw PathAlreadyExistsException as-is
        } catch (e: CancellationException) {
            throw e
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
            if (docFile.existsStrict()) throw PathAlreadyExistsException(path = path)

            val created = createDocumentFile("application/octet-stream", path)
            cacheCreated(path, created)
            invalidateParentLookup(path)

            // Wait for DocumentsProvider to make file queryable (race condition fix)
            waitUntilQueryable(path)
        } catch (e: PathAlreadyExistsException) {
            throw e // Re-throw PathAlreadyExistsException as-is
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "createFile($path) failed: ${e.asLog()}" }
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun openInputStream(path: SAFPath): InputStream = try {
        val docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "openInputStream(): $path -> $docFile" }

        // Note: We don't pre-check readable here - let ContentResolver validate permissions.
        // DocumentsProvider operates in a different permission context than direct file access.
        contentResolver.openInputStream(docFile.uri)
            ?: throw IOException("Couldn't open input stream for $path")
    } catch (e: Exception) {
        log(TAG, WARN) { "openInputStream($path) failed: ${e.asLog()}" }
        throw ReadException(path = path, cause = e)
    }

    override suspend fun openOutputStream(path: SAFPath, append: Boolean): OutputStream = try {
        var docFile = path.resolveDocFile()
        log(TAG, VERBOSE) { "openOutputStream(append=$append): $path -> $docFile" }

        // Match Local's create-on-write semantics (StandardOpenOption.CREATE, both modes).
        // The parent must already exist — createDocumentFile enforces that.
        val created = if (!docFile.existsStrict()) {
            docFile = createDocumentFile("application/octet-stream", path)
            cacheCreated(path, docFile)
            invalidateParentLookup(path)
            true
        } else {
            // Size/mtime become stale once the caller writes
            lookupCache.remove(path)
            false
        }

        val mode = if (append) "wa" else "w"
        val rawStream = try {
            contentResolver.openOutputStream(docFile.uri, mode)
                ?: throw IOException("Couldn't open output stream for $path")
        } catch (e: IOException) {
            if (!created) throw e
            // Freshly created documents may not be openable immediately on slow providers
            waitUntilQueryable(path)
            contentResolver.openOutputStream(docFile.uri, mode)
                ?: throw IOException("Couldn't open output stream for $path")
        }

        // A concurrent lookup during the write may cache partial size/mtime; drop it once the
        // write is finished so fresh metadata is queried.
        object : FilterOutputStream(rawStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

            override fun close() {
                try {
                    super.close()
                } finally {
                    lookupCache.remove(path)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
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

    // SAF has no symlinks, so a path is already its own canonical form.
    override suspend fun canonicalize(path: SAFPath): SAFPath = path

    override suspend fun move(source: SAFPath, destination: SAFPath): MoveOutcome = try {
        log(TAG, VERBOSE) { "move(): $source -> $destination" }
        moveInternal(source, destination)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The failure may have had side effects — drop possibly-stale handles (including cached
        // descendants of a directory) so callers verifying state afterwards resolve freshly by path.
        invalidateSubtree(source)
        invalidateSubtree(destination)
        log(TAG, WARN) { "move($source, $destination) failed: ${e.asLog()}" }
        throw WriteException(path = source, cause = e)
    }

    private suspend fun moveInternal(source: SAFPath, destination: SAFPath): MoveOutcome {
        // Structural refusals — all decided before any contract call, so nothing is mutated.
        if (source.segments.isEmpty() || destination.segments.isEmpty()) {
            return MoveOutcome.NotSupported("Cannot move a SAF tree root")
        }
        if (source.treeRoot != destination.treeRoot) {
            return MoveOutcome.NotSupported("Cross-tree SAF moves are not atomic")
        }
        if (destination.segments.size > source.segments.size &&
            destination.segments.subList(0, source.segments.size) == source.segments
        ) {
            return MoveOutcome.NotSupported("Cannot move a path into its own subtree")
        }
        val sameParent = source.segments.dropLast(1) == destination.segments.dropLast(1)
        val sameName = source.segments.last() == destination.segments.last()
        if (!sameParent && !sameName) {
            // SAF has no atomic reparent+rename; a compound move could strand the document
            // half-moved if the second step fails.
            return MoveOutcome.NotSupported("SAF cannot atomically reparent and rename")
        }

        val sourceDocFile = source.resolveDocFile()
        if (!sourceDocFile.existsStrict()) {
            throw ReadException("Source does not exist", source)
        }
        if (source == destination) return MoveOutcome.Moved // No-op for an existing document
        val sourceIsDirectory = sourceDocFile.isDirectory

        val sourceName = source.segments.last()
        val destName = destination.segments.last()
        val sourceParentDocFile = source.parent!!.resolveDocFile()
        val destParentDocFile = if (sameParent) {
            sourceParentDocFile
        } else {
            destination.parent!!.resolveDocFile().also {
                if (!it.existsStrict() || !it.isDirectory) {
                    throw WriteException("Destination parent does not exist or is not a directory", destination)
                }
            }
        }

        // renameDocument/moveDocument may collide or silently auto-suffix on an existing destination
        if (destination.resolveDocFile().existsStrict()) {
            return MoveOutcome.NotSupported("Destination already exists: $destination")
        }

        val movedDoc: SAFDocFile? = try {
            if (sameParent) {
                sourceDocFile.renameTo(destName)
            } else {
                DocumentsContract.moveDocument(
                    contentResolver,
                    sourceDocFile.uri,
                    sourceParentDocFile.uri,
                    destParentDocFile.uri,
                )?.let { sourceDocFile.copy(uri = it) }
            }
        } catch (e: UnsupportedOperationException) {
            // Providers that don't implement rename/move throw before mutating anything — but
            // NotSupported guarantees no mutation, so verify state instead of trusting that.
            invalidatePath(source)
            invalidatePath(destination)
            val sourceIntact = sourceParentDocFile.findFile(sourceName) != null
            val destAbsent = destParentDocFile.findFile(destName) == null
            if (sourceIntact && destAbsent) {
                return MoveOutcome.NotSupported("Provider does not support move/rename: ${e.message}")
            }
            throw WriteException(
                "Move unsupported but left ambiguous state (sourceIntact=$sourceIntact, destAbsent=$destAbsent)",
                source,
                e,
            )
        }

        if (movedDoc == null) {
            // Null contract result is ambiguous — only report NotSupported if provably nothing
            // moved. Verify via fresh parent/child queries: cached document handles can survive
            // a move under a stable document ID and would lie about the source path.
            invalidatePath(source)
            invalidatePath(destination)
            val sourceIntact = sourceParentDocFile.findFile(sourceName) != null
            val destAbsent = destParentDocFile.findFile(destName) == null
            if (sourceIntact && destAbsent) {
                return MoveOutcome.NotSupported("Provider returned null result, nothing was mutated")
            }
            throw WriteException(
                "Move returned no result and left ambiguous state " +
                    "(sourceIntact=$sourceIntact, destAbsent=$destAbsent)",
                source,
            )
        }

        // The move happened — verify it landed under the requested name (providers may auto-suffix).
        // From here on failures must be loud: the source is gone, a fallback copy would be impossible.
        val resultDoc = try {
            movedDoc.withVerifiedName(destName)
        } catch (e: NameVerificationException) {
            throw WriteException(
                "Moved, but landed as '${e.actualName}' instead of '$destName' (uri=${e.doc.uri})",
                source,
                e,
            )
        }

        // Unconditional subtree invalidation: for files it degrades to the path itself, and the
        // isDirectory probe above is best-effort — don't let a failed probe leave stale descendants.
        invalidateSubtree(destination)
        cacheCreated(destination, resultDoc)
        invalidateSubtree(source)
        invalidateParentLookup(source)
        invalidateParentLookup(destination)

        return MoveOutcome.Moved
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

    /**
     * Wait until a newly created path becomes queryable.
     *
     * Works around SAF DocumentsProvider race condition where newly created files/directories
     * exist but their metadata isn't immediately queryable. Retries lookup with delays.
     *
     * @param path The path to verify
     * @param maxAttempts Maximum number of lookup attempts (default: 3)
     */
    private suspend fun waitUntilQueryable(path: SAFPath, maxAttempts: Int = 3) {
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) delay(50.milliseconds)

            try {
                // Try to lookup the path - if this succeeds, it's queryable
                lookup(path, LookupOptions())
                if (Bugs.isTrace) log(TAG, VERBOSE) { "Path queryable after ${attempt + 1} attempt(s): $path" }
                return // Success!
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == maxAttempts - 1) {
                    // Last attempt failed - log but don't throw
                    // Path was created, just not immediately queryable
                    log(TAG, WARN) {
                        "Path created but not queryable after $maxAttempts attempts: $path - ${e.asLog()}"
                    }
                }
            }
        }
    }

    companion object {
        private val TAG = logTag("Gateway", "SAF", "FileSystemOps")

        private const val INITIAL_CACHE_SIZE = 16
        private const val MAX_CACHE_SIZE = 1000
        private val CACHE_TTL = 10.seconds
    }
}
