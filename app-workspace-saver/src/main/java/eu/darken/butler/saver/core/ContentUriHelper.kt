package eu.darken.butler.saver.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import javax.inject.Inject

/**
 * Helper for extracting metadata from content URIs received via share intents.
 */
@Reusable
class ContentUriHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Information extracted from a content URI.
     */
    data class SourceInfo(
        val uri: Uri,
        val displayName: String,
        val size: Long?,
        val mimeType: String?,
        val isAccessible: Boolean,
        /** Non-empty when this source is saved as a ZIP of these files, see [withBundle]. */
        val bundleMembers: List<BundleMember> = emptyList(),
    )

    /** One file of a bundle source, see [SaverArguments.Default.sourceBundles]. */
    data class BundleMember(
        val uri: Uri,
        val entryName: String,
        val size: Long?,
    )

    /**
     * Extract metadata from a content URI.
     * Handles various URI schemes and fallback scenarios.
     */
    fun extractInfo(uri: Uri): SourceInfo {
        log(TAG) { "extractInfo($uri)" }

        var displayName: String? = null
        var size: Long? = null

        // Try to query content resolver for metadata
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex)
                    }

                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to query URI metadata: ${e.asLog()}" }
        }

        // Fallback: try to get filename from URI path
        if (displayName == null) {
            displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "shared_file"
        }

        // Get MIME type
        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to get MIME type: ${e.asLog()}" }
            null
        }

        // Check accessibility
        val isAccessible = checkAccessibility(uri)

        return SourceInfo(
            uri = uri,
            displayName = displayName,
            size = size,
            mimeType = mimeType,
            isAccessible = isAccessible,
        ).also {
            log(TAG) { "Extracted info: $it" }
        }
    }

    /**
     * Turns [info] into a bundle source of [members]. Its size is the sum of the member sizes, or
     * null when any of them is unknown, and it is only accessible while every member is.
     */
    fun withBundle(info: SourceInfo, members: List<SaverArguments.BundleMember>): SourceInfo {
        val resolved = members.map { member ->
            val uri = member.uri.toUri()
            BundleMember(uri = uri, entryName = member.entryName, size = statSize(uri))
        }
        val sizes = resolved.map { it.size }
        return info.copy(
            size = if (sizes.all { it != null }) sizes.sumOf { it!! } else null,
            isAccessible = resolved.all { checkAccessibility(it.uri) },
            bundleMembers = resolved,
        ).also {
            log(TAG) { "Bundle info: $it" }
        }
    }

    /** [checkAccessibility] for a source, covering every member of a bundle. */
    fun isSourceAccessible(info: SourceInfo): Boolean = if (info.bundleMembers.isEmpty()) {
        checkAccessibility(info.uri)
    } else {
        info.bundleMembers.all { checkAccessibility(it.uri) }
    }

    private fun statSize(uri: Uri): Long? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 }
    } catch (e: Exception) {
        log(TAG, WARN) { "Failed to stat $uri: ${e.asLog()}" }
        null
    }

    /**
     * Check if the content URI is still accessible.
     * URIs from share intents have temporary permissions that can expire.
     */
    fun checkAccessibility(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: SecurityException) {
        log(TAG, WARN) { "URI no longer accessible (permission expired): $e" }
        false
    } catch (e: Exception) {
        log(TAG, ERROR) { "URI accessibility check failed: ${e.asLog()}" }
        false
    }

    companion object {
        private val TAG = logTag("Saver", "ContentUriHelper")
    }
}
