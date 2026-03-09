package eu.darken.butler.common.debug.recorder.core

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.compression.Zipper
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import java.io.File
import javax.inject.Inject

@Reusable
class DebugLogZipper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun zip(logDir: File): File {
        val zipFile = File(logDir.parentFile, "${logDir.name}.zip")
        val tmpFile = File(logDir.parentFile, "${logDir.name}.zip.tmp")
        log(TAG) { "Zipping $logDir to $zipFile" }

        try {
            val logFiles = logDir.listFiles()?.toList()
            require(!logFiles.isNullOrEmpty()) { "No log files found in $logDir" }

            Zipper().zip(logFiles.map { it.path }, tmpFile.path)
            require(tmpFile.length() > 0) { "Zip file is empty: $tmpFile" }

            if (!tmpFile.renameTo(zipFile)) {
                tmpFile.copyTo(zipFile, overwrite = true)
                tmpFile.delete()
            }

            log(TAG) { "Zip created: ${zipFile.length()}B at $zipFile" }
            return zipFile
        } catch (e: Exception) {
            tmpFile.delete()
            zipFile.delete()
            throw e
        }
    }

    fun zipAndGetUri(logDir: File): Uri? {
        val logFiles = logDir.listFiles()?.toList()
        if (logFiles.isNullOrEmpty()) {
            log(TAG, WARN) { "No log files found in $logDir" }
            return null
        }
        val zipFile = zip(logDir)
        return getUriForZip(zipFile)
    }

    fun getUriForZip(zipFile: File): Uri {
        return FileProvider.getUriForFile(
            context,
            BuildConfigWrap.APPLICATION_ID + ".provider",
            zipFile,
        )
    }

    companion object {
        private val TAG = logTag("Debug", "Log", "Zipper")
    }
}
