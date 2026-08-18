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
            // Paired, not two independent lists: a dropped item used to shift the two out of step,
            // so a single surviving URI was described by items[0] - the wrong file's MIME type and
            // subject - whenever the first item was the one that got dropped.
            val shareable = items.mapNotNull { item ->
                getFileUri(item.path)?.let { uri -> item to uri }
            }

            if (shareable.isEmpty()) {
                log(tag, WARN) { "No valid URIs created from ${items.size} items" }
                return null
            }
            if (shareable.size < items.size) {
                log(tag, WARN) { "Sharing ${shareable.size} of ${items.size} items, the rest have no usable URI" }
            }

            if (shareable.size == 1) {
                val (item, uri) = shareable.single()
                createSingleShareIntent(uri, item)
            } else {
                createMultipleShareIntent(shareable.map { it.second }, shareable.map { it.first })
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create share intent: ${e.asLog()}" }
            null
        }
    }

    /**
     * Creates share intent, wraps in chooser, and launches.
     * Convenience method that handles the complete share flow.
     *
     * @param items Items to share
     * @param chooserTitle Title for the share chooser dialog
     * @return true if share launched successfully, false otherwise
     */
    fun shareWithChooser(
        items: List<Item>,
        chooserTitle: String
    ): Boolean {
        val intent = createShareIntent(items) ?: return false

        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        log(tag, INFO) { "Share chooser launched successfully for ${items.size} items" }
        return true

    }

    private fun createSingleShareIntent(
        uri: Uri,
        item: Item
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, item.displayName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun createMultipleShareIntent(
        uris: List<Uri>,
        items: List<Item>
    ): Intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*" // Use generic type for multiple files
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                    // A root/ADB-routed LocalPath still yields a constructible FileProvider URI, but
                    // the provider runs unprivileged and cannot read the file - the receiving app
                    // would only get a permission error, after a chooser that reported success.
                    // Same guard OpenWithIntentUseCase already applies.
                    if (!file.canRead()) {
                        log(tag, WARN) { "File is not readable without elevated access: ${path.path}" }
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
