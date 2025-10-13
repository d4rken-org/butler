package eu.darken.butler.common.files.saf

import android.R.attr.*
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.StructStat
import android.text.TextUtils
import eu.darken.butler.common.asSequence
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.io.useQuietly
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.saf.SAFFileSystemOps.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.Instant


data class SAFDocFile(
    private val context: Context,
    private val resolver: ContentResolver,
    val uri: Uri
) {

    val name: String?
        get() = queryForString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    val exists: Boolean
        get() = queryForString(DocumentsContract.Document.COLUMN_DOCUMENT_ID) != null

    private val mimeType: String?
        get() = queryForString(DocumentsContract.Document.COLUMN_MIME_TYPE)

    val isFile: Boolean
        get() = DocumentsContract.Document.MIME_TYPE_DIR != (mimeType) && mimeType?.isNotEmpty() == true

    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    val writable: Boolean
        get() {
            // Ignore if grant doesn't allow write
            if (!hasPermission(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                return false
            }

            val flags: Int = queryForLong(DocumentsContract.Document.COLUMN_FLAGS)?.toInt() ?: 0

            // Ignore documents without MIME
            if (TextUtils.isEmpty(mimeType)) return false

            // Deletable documents considered writable
            if (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0) {
                return true
            }

            if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType && flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                // Directories that allow create considered writable
                return true
            } else if (!TextUtils.isEmpty(mimeType) && flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0) {
                // Writable normal files considered writable
                return true
            }

            return false
        }

    val readable: Boolean
        get() {
            // Ignore if grant doesn't allow read
            if (!hasPermission(Intent.FLAG_GRANT_READ_URI_PERMISSION)) return false

            // Ignore documents without MIME
            if (TextUtils.isEmpty(mimeType)) return false

            return true
        }

    val lastModified: Instant
        get() = Instant.fromEpochMilliseconds(queryForLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0)

    val length: Long
        get() = queryForLong(DocumentsContract.Document.COLUMN_SIZE) ?: 0


    data class LookupData(
        val fileType: FileType,
        val size: Long,
        val lastModified: Instant,
    )

    fun getLookupData(): LookupData = resolver.query(
        uri,
        arrayOf(
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ),
        null, null, null
    )?.useQuietly { cursor ->
        if (!cursor.moveToFirst()) {
            throw IOException("File metadata not found: $uri")
        }
        val mimeType = cursor.getString(0)
        val size = if (!cursor.isNull(1)) cursor.getLong(1) else 0L
        val lastModifiedMs = if (!cursor.isNull(2)) cursor.getLong(2) else 0L

        LookupData(
            fileType = when {
                mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> FileType.DIRECTORY
                else -> FileType.FILE
            },
            size = size,
            lastModified = Instant.fromEpochMilliseconds(lastModifiedMs)
        )

    } ?: throw IOException("Unable to query metadata: $uri")

    fun createDirectory(name: String): SAFDocFile {
        return createFile(DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    fun createFile(mimeType: String, name: String): SAFDocFile {
        val newFileUri = DocumentsContract.createDocument(resolver, uri, mimeType, name)
        requireNotNull(newFileUri) { "createFile(mimeType=$mimeType, name=$name) failed for $uri" }
        return SAFDocFile(context, resolver, newFileUri)
    }

    // https://commonsware.com/blog/2019/11/23/scoped-storage-stories-documentscontract.html
    @SuppressLint("Recycle")
    fun findFile(name: String): SAFDocFile? {
        val childrenUri: Uri =
            DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))

        val foundUris = resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            "${DocumentsContract.Document.COLUMN_DISPLAY_NAME}=?",
            arrayOf(name),
            null
        )?.useQuietly { cursor ->
            cursor.asSequence()
                .map { Pair(it.getString(0), it.getString(1)) }
                .toList()
        }

        requireNotNull(foundUris) { "Unable to query for $name in $uri" }

        val pair = foundUris.singleOrNull { it.second == name } ?: return null

        return SAFDocFile(context, resolver, DocumentsContract.buildDocumentUriUsingTree(uri, pair.first))
    }

    @SuppressLint("Recycle") fun listFiles(): List<SAFDocFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))

        val foundUris = resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null
        )?.useQuietly { cursor ->
            cursor.asSequence().map { DocumentsContract.buildDocumentUriUsingTree(uri, it.getString(0)) }.toList()
        }

        requireNotNull(foundUris) { "Unable to list files for $uri" }

        return foundUris.map { SAFDocFile(context, resolver, it) }
    }

    /**
     * Lists files with their metadata in a single batched query.
     * This is much more efficient than calling listFiles() + getLookupData() on each result.
     *
     * Instead of N+1 queries (1 for listing, N for metadata), this uses just 1 query.
     * For 10,000 files, this reduces ~10,001 queries to 1 query.
     *
     * @return List of pairs containing the file and its lookup data
     */
    @SuppressLint("Recycle")
    fun listFilesWithLookupData(): List<Pair<SAFDocFile, LookupData>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))

        val results = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null,
            null,
            null
        )?.useQuietly { cursor ->
            cursor.asSequence().map {
                val documentId = it.getString(0)
                val mimeType = it.getString(1)
                val size = if (!it.isNull(2)) it.getLong(2) else 0L
                val lastModifiedMs = if (!it.isNull(3)) it.getLong(3) else 0L

                val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                val docFile = SAFDocFile(context, resolver, fileUri)

                val lookupData = LookupData(
                    fileType = when {
                        mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> FileType.DIRECTORY
                        else -> FileType.FILE
                    },
                    size = size,
                    lastModified = Instant.fromEpochMilliseconds(lastModifiedMs)
                )

                docFile to lookupData
            }.toList()
        }

        requireNotNull(results) { "Unable to list files for $uri" }

        return results
    }

    fun delete(): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: IllegalArgumentException) {
        if (e.message?.contains(FileNotFoundException::class.simpleName!!) == true) false else throw e
    }

    fun setLastModified(lastModified: Instant): Boolean = try {
        val updateValues = ContentValues()
        updateValues.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified.toEpochMilliseconds())
        val updated: Int = resolver.update(uri, updateValues, null, null)
        updated == 1
    } catch (e: Exception) {
        if (Bugs.isDebug) log(SAFGateway.TAG, VERBOSE) { "setModifiedAt($path, $lastModified) not supported: $e" }
        false
    }

    fun setPermissions(permissions: Permissions): Boolean = openPFD(FileMode.WRITE).use { pfd ->
        try {
            Os.fchmod(pfd.fileDescriptor, permissions.mode)
            true
        } catch (e: UnsupportedOperationException) {
            if (Bugs.isDebug) log(SAFGateway.TAG, WARN) { "setPermissions($path, $permissions) not supported: $e" }
            false
        }
    }

    fun setOwnership(ownership: Ownership): Boolean = openPFD(FileMode.WRITE).use { pfd ->
        try {
            Os.fchown(pfd.fileDescriptor, ownership.userId.toInt(), ownership.groupId.toInt())
            true
        } catch (e: UnsupportedOperationException) {
            if (Bugs.isDebug) log(SAFGateway.TAG, VERBOSE) { "setOwnership($path, $ownership) not supported: $e" }
            false
        }
    }

    fun fstat(): StructStat? = try {
        val pfd = openPFD(FileMode.READ)
        pfd.use { Os.fstat(pfd.fileDescriptor) }
    } catch (e: Exception) {
        log(SAFGateway.TAG, WARN) { "Failed to fstat SAFPath: $this: ${e.asLog()}" }
        null
    }

    fun openPFD(mode: FileMode): ParcelFileDescriptor {
        return resolver.openFileDescriptor(uri, mode.value) ?: throw IOException("Couldn't open $uri")
    }

    private fun hasPermission(
        flag: Int
    ): Boolean = context.checkCallingOrSelfUriPermission(uri, flag) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("Recycle")
    private fun queryForString(column: String): String? {
        return try {
            resolver.query(uri, arrayOf(column), null, null, null).useQuietly { c ->
                if (c != null && c.moveToFirst() && !c.isNull(0)) {
                    c.getString(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            if (e !is IllegalArgumentException || !e.toString().contains("is child of")) {
                log(SAFGateway.TAG + ":SAFDocFile", WARN) { "queryForString(column=$column): $e" }
            }

            null
        }
    }

    @SuppressLint("Recycle")
    private fun queryForLong(column: String): Long? {
        return try {
            resolver.query(uri, arrayOf(column), null, null, null).useQuietly { c ->
                if (c != null && c.moveToFirst() && !c.isNull(0)) {
                    c.getLong(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            log(SAFGateway.TAG + ":SAFDocFile", WARN) { "queryForLong(column=$column): $e" }
            null
        }
    }


    override fun toString(): String {
        return "SAFDocFile(uri=$uri)"
    }

    companion object {

        fun buildTreeUri(baseUri: Uri, crumbs: List<String>): Uri {
            val uriBuilder = StringBuilder().apply {
                append(baseUri)
                append("/document/")
                append(Uri.encode(DocumentsContract.getTreeDocumentId(baseUri)))
                if (crumbs.isNotEmpty() && !this.endsWith(Uri.encode(File.separator))) {
                    append(Uri.encode(File.separator))
                }
                crumbs.forEach {
                    if (it != crumbs.first()) append(Uri.encode(File.separator))
                    append(Uri.encode(it))
                }
            }
            return Uri.parse(uriBuilder.toString())
        }

        fun fromTreeUri(context: Context, contentResolver: ContentResolver, treeUri: Uri): SAFDocFile {
            val documentId = if (DocumentsContract.isDocumentUri(context, treeUri)) {
                DocumentsContract.getDocumentId(treeUri)
            } else {
                DocumentsContract.getTreeDocumentId(treeUri)
            }
            return SAFDocFile(
                context,
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            )
        }
    }
}