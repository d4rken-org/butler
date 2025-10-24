package eu.darken.butler.workspace.core.preview

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing workspace preview image cache.
 *
 * Previews are stored as PNG files in the application cache directory.
 * Files are automatically cleaned up by Android when storage is low.
 *
 * Cache location: `/data/data/{package}/cache/workspace_previews/{workspace_id}.png`
 */
@Singleton
class WorkspacePreviewRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val tag = logTag("Workspace", "PreviewRepo")

    private val previewCacheDir: File
        get() = File(context.cacheDir, "workspace_previews").apply {
            if (!exists()) {
                mkdirs()
                log(tag, INFO) { "Created preview cache directory: $absolutePath" }
            }
        }

    /**
     * Get the cached preview file for a workspace.
     *
     * @return File if cached preview exists, null otherwise
     */
    suspend fun getCachedPreview(workspaceId: Workspace.Id): File? = withContext(dispatcherProvider.IO) {
        val file = workspaceId.previewFile
        if (file.exists() && file.length() > 0) {
            log(tag) { "Found cached preview for ${workspaceId.shortTag}: ${file.length()} bytes" }
            file
        } else {
            log(tag) { "No cached preview for ${workspaceId.shortTag}" }
            null
        }
    }

    /**
     * Save a workspace preview bitmap to cache.
     *
     * @param workspaceId The workspace ID
     * @param bitmap The preview bitmap to save
     * @param quality PNG compression quality (0-100)
     * @return The saved file
     */
    suspend fun savePreview(
        workspaceId: Workspace.Id,
        bitmap: Bitmap,
        quality: Int = 90,
    ): File = withContext(dispatcherProvider.IO) {
        val file = workspaceId.previewFile

        try {
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, quality, output)
                output.flush()
            }
            log(tag, INFO) {
                "Saved preview for ${workspaceId.shortTag}: " +
                    "${bitmap.width}x${bitmap.height}, ${file.length()} bytes"
            }
            file
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save preview for ${workspaceId.shortTag}: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Delete the cached preview for a workspace.
     * Called when workspace is closed or deleted.
     */
    suspend fun deletePreview(workspaceId: Workspace.Id): Boolean = withContext(dispatcherProvider.IO) {
        val file = workspaceId.previewFile
        val deleted = file.delete()
        if (deleted) {
            log(tag, INFO) { "Deleted preview for ${workspaceId.shortTag}" }
        }
        deleted
    }

    /**
     * Delete all cached previews.
     * Useful for cleanup or debugging.
     */
    suspend fun clearAllPreviews(): Int = withContext(dispatcherProvider.IO) {
        val files = previewCacheDir.listFiles() ?: emptyArray()
        val count = files.count { it.delete() }
        log(tag, INFO) { "Cleared $count preview files" }
        count
    }

    /**
     * Get the cache directory size in bytes.
     */
    suspend fun getCacheSize(): Long = withContext(dispatcherProvider.IO) {
        val files = previewCacheDir.listFiles() ?: emptyArray()
        files.sumOf { it.length() }
    }

    private val Workspace.Id.previewFile: File
        get() = File(previewCacheDir, "${id}.png")
}
