package eu.darken.butler.common.storage

import android.content.ContentResolver
import android.net.Uri
import dagger.Reusable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.local.toLocalPath
import eu.darken.butler.common.files.saf.SAFGateway
import java.io.File
import javax.inject.Inject

/**
 * Fuck the SAF, this is grating.
 */
@Reusable
class PathMapper @Inject constructor(
    private val contentResolver: ContentResolver,
    private val storageManager2: StorageManager2,
) {

    fun toSAFPath(localPath: LocalPath): SAFPath? {
        return try {
            log(TAG, VERBOSE) { "toSAFPath() called with: $localPath" }

            val osStorage = storageManager2.storageVolumes
                .onEach { log(TAG, VERBOSE) { "Trying to match volume $it against $localPath" } }
                .filter { it.directory != null }
                .firstOrNull { localPath.path.startsWith(it.directory!!.path) }
                ?.also { log(TAG, VERBOSE) { "Target storageVolumes for $localPath is $it" } }
                ?: return null.also { log(TAG, WARN) { "No storage volume found for $localPath" } }

            val prefixFreeFile = if (osStorage.directory!!.path != localPath.path) {
                localPath.path.replace("${osStorage.directory!!.path}${File.separatorChar}", "")
            } else {
                // Permission is equal to path
                ""
            }
            log(TAG, VERBOSE) { "Prefix-free path: '$prefixFreeFile'" }

            val segments = if (prefixFreeFile.isEmpty()) {
                emptyList()
            } else {
                prefixFreeFile.split(File.separator)
            }
            log(TAG, VERBOSE) { "Calculated segments: $segments" }

            SAFPath.build(
                base = osStorage.treeUri,
                segs = segments.toTypedArray(),
            ).also {
                log(TAG) { "toSAFPath() $localPath -> pathUri=${it.pathUri}, segments=${it.segments}" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to map $localPath: ${e.asLog()}" }
            null
        }
    }

    fun toLocalPath(safPath: SAFPath): LocalPath? {
        return try {
            val osStorage = storageManager2.storageVolumes
                .onEach { log(TAG, VERBOSE) { "Trying to match volume $it against $safPath" } }
                .filter { it.directory != null }
                .firstOrNull { safPath.treeRootUri == it.treeUri }
                ?.also { log(TAG) { "Target storageVolumes for $safPath is $it" } }
                ?: return null

            osStorage.directory?.toLocalPath()?.child(*safPath.segments.toTypedArray())
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to map $safPath:${e.asLog()}" }
            null
        }
    }

    companion object {
        val TAG: String = logTag("SAF", "Mapper")
    }
}