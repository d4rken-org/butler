package eu.darken.butler.viewer.core

import android.graphics.Bitmap
import dagger.Reusable
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.write.AtomicFileWriter
import eu.darken.butler.common.files.write.AtomicWriteTargetExistsException
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Renders an APK's launcher icon at full size and writes it out as a PNG.
 *
 * Kept out of [ViewerWorkspace]: the workspace classifies one file and holds that result, while an
 * export is a one-shot user action whose bitmap must not end up in workspace state.
 */
@Reusable
class ApkIconExporter @Inject constructor(
    private val apkArchiveParser: ApkArchiveParser,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val atomicFileWriter = AtomicFileWriter(gatewaySwitch, TAG)

    /** The archive's icon at export resolution, or null if it declares none we can read. */
    suspend fun render(apkPath: APath<*>): Bitmap? = apkArchiveParser.loadIcon(apkPath)

    /** What is already sitting at a save destination. */
    enum class TargetState {
        FREE,

        /** A regular file: replaceable, once the user says so. */
        EXISTS_FILE,

        /**
         * A directory, a symlink, or something the gateway could not classify. Never replaced:
         * writing "over" a symlink follows it and truncates whatever it points at, which is not
         * what anyone agreeing to replace `icon.png` has in mind.
         */
        EXISTS_OTHER,
    }

    suspend fun inspectTarget(target: APath<*>): TargetState = gatewaySwitch.useRes {
        if (!gatewaySwitch.exists(target)) {
            TargetState.FREE
        } else {
            when (gatewaySwitch.lookup(target, LookupOptions.BASE).fileType) {
                FileType.FILE -> TargetState.EXISTS_FILE
                else -> TargetState.EXISTS_OTHER
            }
        }
    }

    /**
     * Encodes [bitmap] into [target].
     *
     * The destination is classified a second time here, immediately before the write: the first
     * classification happened before the picker and any overwrite prompt, and a file that appeared
     * in that window must not inherit permission granted for a destination that was empty.
     *
     * @param overwriteAuthorized the user was shown what is already there and agreed to lose it
     */
    suspend fun save(
        bitmap: Bitmap,
        target: APath<*>,
        overwriteAuthorized: Boolean,
    ) = withContext(dispatcherProvider.IO) {
        log(TAG) { "save(${bitmap.width}x${bitmap.height}, authorized=$overwriteAuthorized) -> $target" }
        gatewaySwitch.useRes {
            when (inspectTarget(target)) {
                TargetState.EXISTS_OTHER -> throw ViewerIconTargetNotAFileException(target)
                TargetState.EXISTS_FILE -> if (!overwriteAuthorized) {
                    throw ViewerIconTargetAppearedException(target)
                }

                TargetState.FREE -> Unit
            }

            // Writes to a sibling temp and swaps it in, so a failure mid-write leaves the existing
            // file intact rather than truncated. `requireAbsent` re-checks at the swap itself: the
            // check above cannot cover the time spent encoding the PNG.
            try {
                atomicFileWriter.replace(
                    target = target,
                    originalAccess = AtomicFileWriter.OriginalAccess.None,
                    requireAbsent = !overwriteAuthorized,
                ) { context ->
                    val encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, context.sink.outputStream())
                    if (!encoded) throw IOException("Failed to encode icon as PNG: $target")
                }
            } catch (e: AtomicWriteTargetExistsException) {
                throw ViewerIconTargetAppearedException(target)
            }
        }
        log(TAG, INFO) { "Icon written to $target" }
    }

    companion object {
        private val TAG = logTag("Viewer", "ApkIconExporter")
    }
}

/** What to do with a chosen icon destination. */
sealed interface IconSaveDecision {
    data class Write(val target: APath<*>) : IconSaveDecision

    /** A replaceable file is already there; the user has to agree to lose it. */
    data class Confirm(val target: APath<*>) : IconSaveDecision

    data class Reject(val error: Throwable) : IconSaveDecision
}

/**
 * Separate from the ViewModel so the one branch that can destroy a user's file is pinned by tests:
 * an occupied destination must never resolve to [IconSaveDecision.Write].
 */
fun decideIconSave(target: APath<*>, state: ApkIconExporter.TargetState): IconSaveDecision = when (state) {
    ApkIconExporter.TargetState.FREE -> IconSaveDecision.Write(target)
    ApkIconExporter.TargetState.EXISTS_FILE -> IconSaveDecision.Confirm(target)
    ApkIconExporter.TargetState.EXISTS_OTHER -> IconSaveDecision.Reject(ViewerIconTargetNotAFileException(target))
}
