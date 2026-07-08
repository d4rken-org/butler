package eu.darken.butler.common.coil

import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import eu.darken.butler.common.previews.SharedContentPreview
import javax.inject.Inject

/**
 * Cache key for [SharedContentPreview]: URI identity + name/size/mime + requested render size, so a
 * different shared item (or a re-share of a changed file) doesn't reuse a stale generated preview.
 */
class SharedContentPreviewKeyer @Inject constructor() : Keyer<SharedContentPreview> {

    override fun key(data: SharedContentPreview, options: Options): String {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val h = (options.size.height as? Dimension.Pixels)?.px ?: 0
        return "shared-preview-${data.uri}-${data.mimeType}-${data.displayName}-${data.size ?: -1L}-${w}x$h"
    }
}
