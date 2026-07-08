package eu.darken.butler.common.previews

import android.net.Uri

/**
 * Coil model for previewing shared `content://` data (an APK's icon or a PDF's first page) WITHOUT
 * materializing the file. Lives in `app-common` so both the fetcher (in `app`) and the Saver UI
 * (in `app-workspace-saver`) can reference it.
 *
 * [displayName] and [size] participate in cache keying and let the fetcher fall back to the file
 * extension when [mimeType] is null/`application/octet-stream`.
 */
data class SharedContentPreview(
    val uri: Uri,
    val mimeType: String?,
    val displayName: String?,
    val size: Long?,
)
