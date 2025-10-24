package eu.darken.butler.common.files.metadata

import android.graphics.BitmapFactory
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts metadata from image files (JPEG, PNG, GIF, BMP, WEBP, etc.).
 *
 * Uses BitmapFactory to extract dimensions without loading the full bitmap.
 *
 * Only supports local file paths.
 *
 * Note: EXIF data extraction can be added later via ExifInterface if needed.
 */
@Singleton
class ImageMetadataExtractor @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) : MetadataExtractor<ImageMetadata> {

    private val tag = logTag("Metadata", "Extractor", "Image")

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg"
    )

    override fun canHandle(lookup: APathLookup<*>): Boolean {
        val extension = lookup.lookedUp.name.substringAfterLast('.', "").lowercase()
        return extension in imageExtensions
    }

    override suspend fun extract(lookup: APathLookup<*>): Result<ImageMetadata> = runCatching {
        withContext(dispatcherProvider.IO) {
            val path = lookup.lookedUp as? LocalPath
                ?: throw UnsupportedOperationException("Image extraction only supports local paths, got: ${lookup.lookedUp::class.simpleName}")

            log(tag) { "Extracting image metadata from: ${path.path}" }

            // Extract dimensions using BitmapFactory (fast, no bitmap loading)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path.path, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw IllegalStateException("Failed to extract image dimensions")
            }

            val format = options.outMimeType?.substringAfter("/")?.uppercase() ?: "UNKNOWN"

            ImageMetadata(
                width = options.outWidth,
                height = options.outHeight,
                format = format,
                exifData = emptyMap()  // EXIF extraction can be added later if needed
            )
        }
    }
}
