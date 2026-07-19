package eu.darken.butler.common.files.archive

/**
 * Options for [ArchiveService.compress]. Deliberately not a data class: generated
 * toString/equals would expose [password] contents.
 *
 * The caller keeps ownership of [password] and wipes it after the write completes.
 */
class ArchiveWriteOptions(
    val format: ArchiveFormat,
    val preset: CompressionPreset = CompressionPreset.NORMAL,
    val password: CharArray? = null,
) {
    init {
        require(password == null || password.isNotEmpty()) { "Password must be null or non-empty" }
        require(password == null || format == ArchiveFormat.ZIP) { "Encryption is only supported for ZIP" }
    }

    override fun toString(): String =
        "ArchiveWriteOptions(format=$format, preset=$preset, password=${if (password != null) "<set>" else "null"})"
}
