package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@SerialName("WEBDAV_LOOKUP")
data class WebDavPathLookup(
    override val lookedUp: WebDavPath,
    override val fileType: FileType,
    override val size: Long?,
    @Contextual override val modifiedAt: Instant?,
    override val error: String? = null,
    override val ownership: Ownership? = null,
    override val permissions: Permissions? = null,
    @Contextual override val createdAt: Instant? = null,
    /** The server's `getetag` as sent, quotes included, e.g. `"5f1c-64a2b3"`. */
    val etag: String? = null,
    /** The server's `getcontenttype`, e.g. `image/jpeg`. */
    val contentType: String? = null,
) : APathLookup<WebDavPath> {

    /** WebDAV has no symbolic links. */
    override val target: WebDavPath?
        get() = null
}
