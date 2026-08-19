package eu.darken.butler.workspace.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a file to another app via ACTION_VIEW, always through a chooser so the user keeps control
 * over which app opens it.
 */
@Singleton
class OpenWithIntentUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tag = logTag("Workspace", "OpenWithIntentUseCase")

    /**
     * The bare ACTION_VIEW intent. It carries no FLAG_ACTIVITY_NEW_TASK on purpose: it is meant to
     * be wrapped in a chooser, and the flag belongs on whatever is actually started.
     */
    fun createViewIntent(path: APath<*>, mime: String): Intent? {
        val uri = getFileUri(path) ?: return null

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Wraps the view intent in a chooser and launches it. Returns false when there is no usable URI,
     * no app that can handle the file, or the launch itself was refused.
     */
    fun openWithChooser(path: APath<*>, mime: String, chooserTitle: CharSequence): Boolean {
        log(tag) { "openWithChooser($path, $mime)" }

        val viewIntent = createViewIntent(path, mime) ?: return false

        // Query the inner ACTION_VIEW: the chooser activity itself always resolves, so asking it
        // would report a handler even when nothing can open the file.
        if (!hasHandler(viewIntent)) {
            log(tag, WARN) { "No app can handle $mime for $path" }
            return false
        }

        val chooser = Intent.createChooser(viewIntent, chooserTitle).apply {
            // The chooser is what gets started from the application context, so it needs the flag.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            context.startActivity(chooser)
            log(tag, INFO) { "Open-with chooser launched for $path" }
            true
        } catch (e: ActivityNotFoundException) {
            log(tag, WARN) { "No activity to show the chooser: ${e.asLog()}" }
            false
        } catch (e: SecurityException) {
            log(tag, ERROR) { "Not allowed to launch the chooser: ${e.asLog()}" }
            false
        }
    }

    /**
     * Whether any app *other than Butler* can view the intent.
     *
     * Butler registers an `ACTION_VIEW` filter for every file so it appears in other apps'
     * "Open with", which means it always answers this query about itself. Counting that would make
     * the check trivially true and turn every chooser into one that can offer nothing but the app
     * the user is already in, so the own package is excluded.
     */
    private fun hasHandler(intent: Intent): Boolean = try {
        context.packageManager
            .queryIntentActivities(intent, 0)
            .any { it.activityInfo?.packageName != context.packageName }
    } catch (e: Exception) {
        log(tag, WARN) { "Failed to check intent handlers: ${e.asLog()}" }
        false
    }

    private fun getFileUri(path: APath<*>): Uri? = when (path) {
        is LocalPath -> {
            val file = path.file
            when {
                !file.exists() -> {
                    log(tag, WARN) { "File does not exist: $path" }
                    null
                }
                // A root/ADB-routed LocalPath still yields a constructible FileProvider URI, but the
                // provider runs unprivileged and cannot read the file - the receiving app would only
                // get a permission error.
                !file.canRead() -> {
                    log(tag, WARN) { "File is not readable without elevated access: $path" }
                    null
                }
                else -> try {
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to build a FileProvider URI for $path: ${e.asLog()}" }
                    null
                }
            }
        }

        else -> {
            log(tag, WARN) { "Unsupported path type for open-with: ${path::class.simpleName}" }
            null
        }
    }
}

/**
 * No installed app could open the file, or Butler could not hand it over.
 */
class NoAppForFileException(
    val fileName: String,
) : IllegalStateException("No other app found to open file: $fileName"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.workspace_open_with_no_app_label.toCaString(),
        description = caString { it.getString(R.string.workspace_open_with_no_app_description, fileName) },
    )
}
