package eu.darken.butler.common.pkgs.sources

import android.content.pm.SharedLibraryInfo
import android.os.Parcel
import dagger.Reusable
import eu.darken.butler.common.collections.toHex
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import javax.inject.Inject

/**
 * Extracts a shared library's file path from [SharedLibraryInfo] by marshalling it to a Parcel
 * and scraping the path out of the raw bytes — no public API exposes the path directly. Fragile
 * to framework changes, but no better option exists.
 */
@Reusable
class SharedLibraryPathClaw @Inject constructor() {

    fun clawOutPath(libraryInfo: SharedLibraryInfo): LocalPath? {
        val rawParcel = try {
            val parcel = Parcel.obtain().apply {
                writeParcelable(libraryInfo, 0)
                setDataPosition(0)
            }
            parcel.marshall().also { parcel.recycle() }
        } catch (e: Exception) {
            log(TAG) { "Library claw failed on $libraryInfo: ${e.asLog()}" }
            null
        }

        return rawParcel?.let { clawOutPath(libraryInfo.name, it) }
    }

    fun clawOutPath(pkgName: String, rawBytes: ByteArray): LocalPath? {
        val path = try {
            val rawString = String(rawBytes)
            val rawStringCleaned = rawString.replace("\u0000", "")

            if (Bugs.isDebug) {
                val hexBytes = rawBytes.toHex()
                log(TAG, VERBOSE) { "Clawing ($pkgName) HEX: $hexBytes" }
                log(TAG, VERBOSE) { "Clawing ($pkgName) STRING: $rawString" }
                log(TAG, VERBOSE) { "Clawing ($pkgName) STRING CLEAN: $rawStringCleaned" }
            }

            getClawPatterns(pkgName).firstNotNullOfOrNull {
                it.find(rawStringCleaned)?.groupValues?.getOrNull(1)
            }
        } catch (e: Exception) {
            log(TAG) { "Library claw failed for $pkgName on $rawBytes: ${e.asLog()}" }
            null
        }
        log(TAG) { "Clawed out library path: $path" }
        return path?.let { LocalPath.build(path) }
    }


    companion object {
        private val LIBRARY_DATA_PATH_CLAW = Regex("^.+(/data/.+?\\.apk).+\$")
        private val LIBRARY_PRODUCT_PATH_CLAW = Regex("^.+(/product/.+?\\.apk).+\$")
        private val LIBRARY_GENERIC_PATH_CLAW = Regex("((?:/\\w+)+/.+\\.apk)")

        private fun getClawPatterns(pkgName: String): Set<Regex> = setOf(
            Regex("((?:/\\w+)+/.+\\.apk)\\W+#\\W+(${Regex.escape(pkgName)})"),
            LIBRARY_DATA_PATH_CLAW,
            LIBRARY_PRODUCT_PATH_CLAW,
            LIBRARY_GENERIC_PATH_CLAW,
        )

        private val TAG = logTag("Pkg", "Repo", "Source", "SharedLibrary", "PathClaw")
    }
}