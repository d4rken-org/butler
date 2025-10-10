package eu.darken.butler.common.files

import kotlinx.serialization.Serializable

@Serializable
data class MimeInfo(
    val rawType: String,
) {
    val isImage: Boolean
        get() = rawType.startsWith("image/")

    val isVideo: Boolean
        get() = rawType.startsWith("video/")

    val isAudio: Boolean
        get() = rawType.startsWith("audio/")

    val isText: Boolean
        get() = rawType.startsWith("text/") || rawType in TEXT_MIME_TYPES

    companion object {
        private val TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-sh",
            "application/x-shellscript",
        )
    }
}
