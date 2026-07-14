package eu.darken.butler.common.files.archive

/**
 * Archive formats supported for browsing/extraction. [ZIP] additionally supports creation.
 *
 * Detection is filename-based because compound suffixes (`.tar.gz`) cannot be distinguished
 * from their plain compression counterparts (`.gz`) via single-extension MIME lookups.
 */
enum class ArchiveFormat(val displayExtension: String) {
    ZIP("zip"),
    TAR("tar"),
    TAR_GZ("tar.gz"),
    TAR_BZ2("tar.bz2"),
    ;

    companion object {
        fun fromFileName(fileName: String): ArchiveFormat? {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".zip") -> ZIP
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZ
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> TAR_BZ2
                lower.endsWith(".tar") -> TAR
                else -> null
            }
        }
    }
}
