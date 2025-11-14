package eu.darken.butler.workspace.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import java.io.File
import javax.inject.Inject

/**
 * Use case for creating share intents for files across different workspaces.
 * Handles FileProvider URI creation and intent construction for single and multiple files.
 */
class ShareIntentUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = logTag("Workspace", "ShareIntentUseCase")

    /**
     * Generic item interface that workspaces can convert their specific types to
     */
    interface Item {
        val path: APath<*>
        val mimeType: String?
        val displayName: String
    }

    /**
     * Creates a share intent for the given items.
     * Returns ACTION_SEND for single item, ACTION_SEND_MULTIPLE for multiple items.
     * Returns null if no valid URIs could be created.
     *
     * Note: The returned intent is unwrapped - callers should wrap in Intent.createChooser()
     */
    fun createShareIntent(items: List<Item>): Intent? {
        log(tag) { "createShareIntent(): ${items.size} items" }

        if (items.isEmpty()) {
            log(tag, WARN) { "No items to share" }
            return null
        }

        return try {
            val uris = items.mapNotNull { item ->
                getFileUri(item.path)
            }

            if (uris.isEmpty()) {
                log(tag, WARN) { "No valid URIs created from ${items.size} items" }
                return null
            }

            if (uris.size == 1) {
                createSingleShareIntent(uris[0], items[0])
            } else {
                createMultipleShareIntent(uris, items)
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create share intent: ${e.asLog()}" }
            null
        }
    }

    private fun createSingleShareIntent(uri: Uri, item: Item): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, item.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createMultipleShareIntent(uris: List<Uri>, items: List<Item>): Intent {
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*" // Use generic type for multiple files
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun getFileUri(path: APath<*>): Uri? {
        return try {
            when (path) {
                is LocalPath -> {
                    val file = File(path.path)
                    if (!file.exists()) {
                        log(tag, WARN) { "File does not exist: ${path.path}" }
                        return null
                    }

                    // Use FileProvider for secure access to files
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        file
                    )
                }
                else -> {
                    log(tag, WARN) { "Unsupported path type for sharing: ${path::class.simpleName}" }
                    null
                }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get URI for path $path: ${e.asLog()}" }
            null
        }
    }
}
