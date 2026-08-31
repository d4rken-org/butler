package eu.darken.butler.common

import android.webkit.MimeTypeMap
import dagger.Reusable
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.MimeInfo
import java.util.Locale
import javax.inject.Inject

@Reusable
class MimeTypeTool @Inject constructor() {

    /**
     * Butler's own table answers first, so a thumbnail agrees with what every workspace shows for
     * the same file. MimeTypeMap covers what the table does not know, e.g. tiff or jfif, which
     * would otherwise lose their thumbnails.
     */
    suspend fun determineMimeType(lookup: APathLookup<*>): String {
        val own = MimeInfo.fromFileName(lookup.name).rawType
        if (own != MimeTypes.Unknown.value) return own

        val ext = lookup.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: MimeTypes.Unknown.value
    }
}
