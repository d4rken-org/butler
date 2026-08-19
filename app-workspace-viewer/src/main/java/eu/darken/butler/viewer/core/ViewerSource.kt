package eu.darken.butler.viewer.core

import android.net.Uri
import androidx.core.net.toUri
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments

/**
 * What a viewer is showing, independent of whether it lives on a filesystem.
 *
 * This is the identity, not the access: it decides the renderer ([mime]), what the chrome says
 * ([displayName], [folderPath]), which cache entry belongs to it ([cacheKey]) and which actions the
 * action bar may offer. Reading the bytes is [ViewerContentReader]'s job, and the two are separate
 * because the image pipeline dispatches on the KIND of source (telephoto has a first-class
 * content-URI source that opens a fresh stream per decoder) rather than merely asking for bytes.
 */
sealed interface ViewerSource {

    /** What the chrome shows as the file's name. */
    val displayName: String

    /** The folder to show, or null when the content has no location worth showing. */
    val folderPath: String?

    /**
     * Which renderer runs. Deliberately NOT derived from [displayName] at the use sites: content
     * arriving from another app routinely has no extension, and name-based classification would
     * send a JPEG called "IMG_4821" to the whole-bitmap decoder instead of the tiling one.
     */
    val mime: MimeInfo

    /** Stable, collision-free identity for image caches. */
    val cacheKey: String

    /** A real file on the device, with all the file actions that implies. */
    data class Stored(val path: APath<*>) : ViewerSource {
        override val displayName: String get() = path.name
        override val folderPath: String? get() = path.parent?.path ?: path.path
        override val mime: MimeInfo get() = MimeInfo.fromFileName(path.name)
        override val cacheKey: String get() = path.path
    }

    /**
     * Content read through a ContentProvider grant. Readable for as long as the task that received
     * the share lives; it has no location, cannot be deleted or moved, and cannot be handed onward.
     */
    data class Streamed(
        val uri: Uri,
        override val displayName: String,
        override val mime: MimeInfo,
        val sizeBytes: Long?,
        val arrivalId: String,
    ) : ViewerSource {
        override val folderPath: String? get() = null

        // Keyed by arrival, not by URI: providers reuse document ids, so two shares of "the same"
        // URI must not serve each other's bytes from cache.
        override val cacheKey: String get() = "$arrivalId:$uri"
    }
}

/** The source these arguments describe. */
fun ViewerArguments.toViewerSource(): ViewerSource = when (this) {
    is ViewerArguments.Default -> ViewerSource.Stored(filePath)
    is ViewerArguments.Streamed -> ViewerSource.Streamed(
        uri = uriString.toUri(),
        displayName = displayName,
        mime = MimeInfo(mimeType),
        sizeBytes = sizeBytes,
        arrivalId = arrivalId,
    )
}
