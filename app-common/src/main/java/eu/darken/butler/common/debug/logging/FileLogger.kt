package eu.darken.butler.common.debug.logging

import android.util.Log
import eu.darken.butler.common.debug.Bugs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.time.Clock


/**
 * @param worldReadable when true the log file is made world read/writable (needed for the legacy
 *   external-storage debug logs). Bug-report recordings pass `false`, so `FileLogger` does not make
 *   their log files world readable or writable.
 */
class FileLogger(
    private val logFile: File,
    private val worldReadable: Boolean = true,
) : Logging.Logger {
    private var logWriter: OutputStreamWriter? = null

    /** @return true if the writer is ready and the logger is receiving lines. */
    @Suppress("SetWorldWritable", "SetWorldReadable")
    @Synchronized
    fun start(): Boolean {
        if (logWriter != null) return true
        Log.i(TAG, "Starting logger for " + logFile.path)
        try {
            logFile.parentFile!!.mkdirs()
            if (logFile.createNewFile()) Log.i(TAG, "File logger writing to ${logFile.path}")
            if (worldReadable) {
                if (logFile.setReadable(true, false)) Log.i(TAG, "Debug run log read permission set")
                if (logFile.setWritable(true, false)) Log.i(TAG, "Debug run log write permission set")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Log writer failed to init log file", e)
            e.printStackTrace()
        }

        return try {
            logWriter = OutputStreamWriter(FileOutputStream(logFile, true))
            logWriter!!.write("=== BEGIN ${Bugs.processTag} ===\n")
            logWriter!!.write("Logfile: $logFile\n")
            logWriter!!.flush()
            Log.i(TAG, "File logger started.")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Log writer failed to start", e)
            e.printStackTrace()

            try {
                logWriter?.close()
            } catch (ignore: IOException) {
            }
            logWriter = null
            logFile.delete()
            false
        }
    }

    @Synchronized
    fun stop() {
        logWriter?.let {
            logWriter = null
            try {
                it.write("=== END ===\n")
                it.close()
            } catch (ignore: IOException) {
            }
            Log.i(TAG, "File logger stopped.")
        }
    }

    override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
        logWriter?.let {
            try {
                it.write("${Clock.System.now()}  ${priority.shortLabel}/$tag: $message\n")
                it.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write log line.", e)
                try {
                    it.close()
                } catch (ignore: Exception) {
                }
                logWriter = null
            }
        }
    }

    override fun toString(): String = "FileLogger(file=$logFile)"

    companion object {
        private val TAG = logTag("Debug", "FileLogger")
    }
}

