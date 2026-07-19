package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Last-used compress dialog choices. Deliberately has no password field — passwords are never persisted. */
@Serializable
data class ArchiveCompressionDefaults(
    @SerialName("format") val format: ArchiveFormat = ArchiveFormat.ZIP,
    @SerialName("level") val level: CompressionPreset = CompressionPreset.NORMAL,
)
