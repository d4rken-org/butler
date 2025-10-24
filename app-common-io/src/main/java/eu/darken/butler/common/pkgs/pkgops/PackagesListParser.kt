package eu.darken.butler.common.pkgs.pkgops

import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.metadata.AndroidSystemIds
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses Android's `/data/system/packages.list` file to extract app UID mappings.
 *
 * The packages.list file contains information about all installed apps:
 * Format: `packageName UID debugFlag dataPath seinfo gids`
 * Example: `com.android.phone 10001 0 /data/user/0/com.android.phone default:targetSdkVersion=34 none 1`
 *
 * This file is world-readable on most Android devices and provides accurate
 * UID → package name mappings for all installed apps (UIDs 10000+).
 */
@Singleton
class PackagesListParser @Inject constructor() {

    private var filePath: String = PACKAGES_LIST_PATH

    /**
     * Constructor for testing with custom file path.
     */
    constructor(filePath: String) : this() {
        this.filePath = filePath
    }

    /**
     * Parses the packages.list file and returns a map of UID → package name.
     *
     * @return Map of app UIDs to package names, or empty map if parsing fails
     */
    fun parse(): Map<Int, String> {
        val packagesFile = File(filePath)

        if (!packagesFile.exists()) {
            log(TAG, Logging.Priority.VERBOSE) { "parse(): $filePath does not exist" }
            return emptyMap()
        }

        if (!packagesFile.canRead()) {
            log(TAG, Logging.Priority.VERBOSE) { "parse(): $filePath is not readable" }
            return emptyMap()
        }

        return try {
            val result = mutableMapOf<Int, String>()
            var lineCount = 0
            var parseErrors = 0

            packagesFile.useLines { lines ->
                lines.forEach { line ->
                    lineCount++
                    parseLine(line)?.let { (uid, packageName) ->
                        result[uid] = packageName
                    } ?: run {
                        parseErrors++
                    }
                }
            }

            log(
                TAG,
                Logging.Priority.VERBOSE
            ) { "parse(): parsed $lineCount lines, found ${result.size} mappings, $parseErrors errors" }
            result
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "parse() failed: ${e.asLog()}" }
            emptyMap()
        }
    }

    /**
     * Parses a single line from packages.list.
     *
     * Format: `packageName UID debugFlag dataPath seinfo gids`
     * Example: `com.android.phone 10001 0 /data/user/0/com.android.phone default:targetSdkVersion=34 none 1`
     *
     * @param line A line from packages.list
     * @return Pair of (UID, packageName) or null if parsing fails
     */
    internal fun parseLine(line: String): Pair<Int, String>? {
        if (line.isBlank() || line.startsWith("#")) {
            return null
        }

        return try {
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 2) {
                return null
            }

            val packageName = parts[0]
            val uid = parts[1].toIntOrNull() ?: return null

            // Only include app UIDs (10000+)
            if (uid < AndroidSystemIds.AID_APP_START) {
                return null
            }

            Pair(uid, packageName)
        } catch (e: Exception) {
            log(TAG, Logging.Priority.VERBOSE) { "parseLine('$line') failed: ${e.asLog()}" }
            null
        }
    }

    companion object {
        /**
         * Path to Android's packages.list file.
         * This file is updated by PackageManager when apps are installed/uninstalled.
         */
        const val PACKAGES_LIST_PATH = "/data/system/packages.list"
        private val TAG = logTag("Gateway", "Local", "FileSystemOps", "Ownership", "Resolver", "PackagesListParser")
    }
}