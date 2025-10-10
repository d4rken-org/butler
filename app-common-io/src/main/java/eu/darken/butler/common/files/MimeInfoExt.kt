package eu.darken.butler.common.files

import android.content.Context
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString

fun MimeInfo.toUserFriendlyName(context: Context): String {
    return toCaString().get(context)
}

fun MimeInfo.toCaString(): CaString {
    return when {
        // Images
        rawType.startsWith("image/") -> R.string.common_mimetype_image.toCaString()

        // Videos
        rawType.startsWith("video/") -> R.string.common_mimetype_video.toCaString()

        // Audio
        rawType.startsWith("audio/") -> R.string.common_mimetype_audio.toCaString()

        // Specific text types
        rawType == "text/plain" -> R.string.common_mimetype_text_plain.toCaString()
        rawType.startsWith("text/") -> R.string.common_mimetype_text.toCaString()

        // Documents
        rawType == "application/pdf" -> R.string.common_mimetype_pdf.toCaString()
        rawType == "application/json" -> R.string.common_mimetype_json.toCaString()
        rawType == "application/xml" || rawType == "text/xml" -> R.string.common_mimetype_xml.toCaString()

        rawType in setOf(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ) -> R.string.common_mimetype_document.toCaString()

        // Archives
        rawType in setOf(
            "application/zip",
            "application/x-zip",
            "application/x-tar",
            "application/gzip",
            "application/x-7z-compressed",
            "application/vnd.rar",
        ) -> R.string.common_mimetype_archive.toCaString()

        // Android
        rawType == "application/vnd.android.package-archive" -> R.string.common_mimetype_apk.toCaString()

        // Unknown
        else -> R.string.common_mimetype_unknown.toCaString()
    }
}
