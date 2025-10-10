package eu.darken.butler.explorer.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.engine.ExplorerItem
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileIntentHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = logTag("FileIntentHelper")

    fun openFileWith(item: ExplorerItem.File): Intent? {
        log(tag) { "openFileWith(${item.lookup.name})" }

        return try {
            val uri = getFileUri(item.lookup.lookedUp) ?: return null

            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType.rawType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create VIEW intent for ${item.lookup.name}: ${e.message}" }
            null
        }
    }

    fun shareFile(item: ExplorerItem.File): Intent? {
        log(tag) { "shareFile(${item.lookup.name})" }

        return try {
            val uri = getFileUri(item.lookup.lookedUp) ?: return null

            Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.rawType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, item.lookup.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create SEND intent for ${item.lookup.name}: ${e.message}" }
            null
        }
    }

    fun shareFiles(items: List<ExplorerItem.File>): Intent? {
        log(tag) { "shareFiles(${items.size} items)" }

        return try {
            val uris = items.mapNotNull { item ->
                getFileUri(item.lookup.lookedUp)
            }

            if (uris.isEmpty()) return null

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*" // Use generic type for multiple files
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create SEND_MULTIPLE intent: ${e.message}" }
            null
        }
    }

    fun canHandleIntent(intent: Intent): Boolean {
        return try {
            val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
            resolveInfos.isNotEmpty()
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to check intent handlers: ${e.message}" }
            false
        }
    }

    private fun getFileUri(path: APath): Uri? {
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
                        "${context.packageName}.fileprovider",
                        file
                    )
                }
                else -> {
                    log(tag, WARN) { "Unsupported path type for intents: ${path::class.simpleName}" }
                    null
                }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to get URI for path $path: ${e.message}" }
            null
        }
    }

    companion object {
        private const val FILE_PROVIDER_AUTHORITY = ".fileprovider"
    }
}