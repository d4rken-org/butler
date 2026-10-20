package eu.darken.butler.common.files.archive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Archive formats supported for browsing and extraction. All but [SEVEN_Z] can also be created
 * (see [isWritable] and [ArchiveService.compress]).
 *
 * Detection is filename-based because compound suffixes (`.tar.gz`) cannot be distinguished
 * from their plain compression counterparts (`.gz`) via single-extension MIME lookups.
 */
@Serializable
enum class ArchiveFormat(val displayExtension: String) {
    @SerialName("zip") ZIP("zip"),
    @SerialName("tar") TAR("tar"),
    @SerialName("tar_gz") TAR_GZ("tar.gz"),
    @SerialName("tar_bz2") TAR_BZ2("tar.bz2"),
    @SerialName("seven_z") SEVEN_Z("7z"),
    ;

    val capabilities: ArchiveCapabilities
        get() = when (this) {
            ZIP -> ZipArchiveHandler.CAPABILITIES
            TAR, TAR_GZ, TAR_BZ2 -> TarArchiveHandler.CAPABILITIES
            SEVEN_Z -> SevenZArchiveHandler.CAPABILITIES
        }

    val isWritable: Boolean get() = capabilities.writable

    /** Can be extracted front to back when the storage offers no random access. */
    val supportsSequentialExtractionFallback: Boolean get() = capabilities.sequentialExtractionFallback

    companion object {
        fun fromFileName(fileName: String): ArchiveFormat? {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".zip") -> ZIP
                // App-install bundles are plain zips; browsing them is how their contents get seen.
                lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") -> ZIP
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZ
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> TAR_BZ2
                lower.endsWith(".tar") -> TAR
                lower.endsWith(".7z") -> SEVEN_Z
                else -> null
            }
        }

        // Compound suffixes first so ".tar.gz" is stripped whole rather than leaving ".tar".
        private val STEM_SUFFIXES = listOf(
            ".tar.gz", ".tar.bz2", ".tbz2", ".tgz", ".tar", ".apks", ".xapk", ".apkm", ".zip", ".7z",
        )

        /**
         * File name without its archive extension, e.g. "backup.tar.gz" -> "backup". Handles compound
         * and alias suffixes. Falls back to the pre-last-dot stem, then the full name, so a name that is
         * only an extension (".tar.gz") is returned unchanged rather than mangled into ".tar".
         */
        fun stemOf(fileName: String): String {
            val lower = fileName.lowercase()
            val suffix = STEM_SUFFIXES.firstOrNull { lower.endsWith(it) }
            if (suffix != null) return fileName.dropLast(suffix.length).ifBlank { fileName }
            return fileName.substringBeforeLast('.').ifBlank { fileName }
        }
    }
}
