package eu.darken.butler.provider.documents.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encodes and decodes Document IDs for Android's DocumentsProvider API.
 *
 * Document ID Format: `{pathType}|{base64EncodedPathData}`
 *
 * **Examples:**
 * - LocalPath: `local|L3N0b3JhZ2UvZW11bGF0ZWQvMC9maWxlLnBkZg`
 * - SAFPath: `saf|eyJ0cmVlUm9vdCI6ImNvbnRlbnQ6Ly8uLi4iLCJzZWdtZW50cyI6WyJmaWxlLnR4dCJdfQ`
 *
 * **Critical Requirements:**
 * - Document IDs must be STABLE (never change except during rename)
 * - Must be reversible (encode → decode → original path)
 * - Must handle all path types, special characters, Unicode, and edge cases
 *
 * **Design Rationale:**
 * - Path alone is globally unique on device (no need for separate rootId)
 * - Base64 URL-safe encoding handles special characters safely
 * - JSON serialization for complex types (SAFPath with treeRoot + segments)
 */
@Singleton
class DocumentIdCodec @Inject constructor(
    private val json: Json,
) {

    /**
     * Encodes an APath into a stable Document ID.
     *
     * @param path The path to encode (LocalPath, SAFPath)
     * @return Document ID in format: `{pathType}|{base64EncodedPathData}`
     */
    fun encode(path: APath<*>): String {
        val pathType = when (path) {
            is LocalPath -> "local"
            is SAFPath -> "saf"
            // Archive contents are not exposed to other apps via the DocumentsProvider.
            is ArchivePath -> throw IllegalArgumentException("Archive paths have no document ID: $path")
            // Neither is network storage: another app holding a document ID would make Butler
            // reconnect and re-authenticate on its behalf.
            is SmbPath -> throw IllegalArgumentException("SMB paths have no document ID: $path")
        }

        val encodedData = when (path) {
            is LocalPath -> {
                val pathString = path.path
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    pathString.toByteArray(Charsets.UTF_8)
                )
            }
            is SAFPath -> {
                val jsonString = json.encodeToString(SAFPath.serializer(), path)
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    jsonString.toByteArray(Charsets.UTF_8)
                )
            }
            is ArchivePath -> throw IllegalArgumentException("Archive paths have no document ID: $path")
            is SmbPath -> throw IllegalArgumentException("SMB paths have no document ID: $path")
        }

        val documentId = "$pathType$SEPARATOR$encodedData"
        log(TAG, VERBOSE) { "Encoded: ${path.path} → $documentId" }
        return documentId
    }

    /**
     * Decodes a Document ID back into an APath.
     *
     * @param documentId Document ID in format: `{pathType}|{base64EncodedPathData}`
     * @return The original APath (LocalPath, SAFPath, etc.)
     * @throws IllegalArgumentException if document ID is malformed or path type is unknown
     */
    fun decode(documentId: String): APath<*> {
        val parts = documentId.split(SEPARATOR)
        require(parts.size == 2) {
            "Invalid document ID format: expected 2 parts (pathType|base64), got ${parts.size}: $documentId"
        }

        val (pathType, encodedData) = parts

        val decodedBytes = try {
            Base64.getUrlDecoder().decode(encodedData)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 encoding in document ID: $documentId", e)
        }

        val path = when (pathType) {
            "local" -> {
                val pathString = String(decodedBytes, Charsets.UTF_8)
                LocalPath.build(pathString)
            }
            "saf" -> {
                val jsonString = String(decodedBytes, Charsets.UTF_8)
                json.decodeFromString(SAFPath.serializer(), jsonString)
            }
            else -> throw IllegalArgumentException("Unknown path type: $pathType (document ID: $documentId)")
        }

        log(TAG, VERBOSE) { "Decoded: $documentId → ${path.path}" }
        return path
    }

    /**
     * Checks if a document ID represents a virtual document (not a real filesystem path).
     *
     * Virtual documents include:
     * - Root: "butler"
     * - Connections: "device|self", "ssh|{id}", "ftp|{id}"
     *
     * @return true if virtual, false if real path (local|*, saf|*)
     */
    fun isVirtualDocument(documentId: String): Boolean {
        return documentId == ProviderLocation.Root.Butler.rootDocumentId ||
            documentId.startsWith("device|")
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "IdCodec")
        private const val SEPARATOR = "|"
    }
}
