package eu.darken.butler.common.files.archive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** User-facing compression effort for archive creation. Format-specific mapping happens in [ArchiveService]. */
@Serializable
enum class CompressionPreset {
    @SerialName("fast") FAST,
    @SerialName("normal") NORMAL,
    @SerialName("best") BEST,
    ;
}
