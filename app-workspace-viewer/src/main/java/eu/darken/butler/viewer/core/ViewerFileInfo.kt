package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlin.time.Instant

/**
 * Metadata shown in the viewer's bottom info card. Every field is nullable because the gateway
 * legitimately returns nothing for it on some storage types (SAF has no ownership or POSIX mode).
 */
data class ViewerFileInfo(
    val size: Long? = null,
    val modifiedAt: Instant? = null,
    val createdAt: Instant? = null,
    val permissions: Permissions? = null,
    val ownership: Ownership? = null,
    val imageInfo: ImageInfo? = null,
) {
    data class ImageInfo(
        val format: String,
        val width: Int? = null,
        val height: Int? = null,
    )
}
