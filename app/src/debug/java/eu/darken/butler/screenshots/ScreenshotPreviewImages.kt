package eu.darken.butler.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R as IoR
import eu.darken.butler.common.pkgs.features.Installed
import coil3.Image as CoilImage

/**
 * Renders stand-in images for the Play Store screenshots.
 *
 * Coil never resolves a real image under layoutlib: the fetchers need a gateway, a package manager
 * and disk access, none of which exist in a preview render. Without this handler every file row is
 * iconless and every app row falls back to the same grey placeholder.
 *
 * Everything it produces is derived from the request data alone, so a render is reproducible.
 */
@OptIn(ExperimentalCoilApi::class)
internal object ScreenshotImagePreviewHandler : AsyncImagePreviewHandler {

    override suspend fun handle(
        imageLoader: ImageLoader,
        request: ImageRequest,
    ): AsyncImagePainter.State = when (val data = request.data) {
        is APathLookup<*> -> pathState(data, request)
        is Installed -> appIconState(data.id.name, request)
        // Every Coil request of every screenshot passes through here; anything this handler does
        // not know about has to keep its normal preview behaviour.
        else -> AsyncImagePreviewHandler.Default.handle(imageLoader, request)
    }

    private fun pathState(
        lookup: APathLookup<*>,
        request: ImageRequest,
    ): AsyncImagePainter.State {
        val name = lookup.lookedUp.name
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            lookup.fileType == FileType.DIRECTORY -> iconState(R.drawable.ic_folder, request)
            lookup.fileType == FileType.SYMBOLIC_LINK -> iconState(R.drawable.ic_file_link, request)
            lookup.fileType == FileType.UNKNOWN -> iconState(R.drawable.ic_file_unknown, request)
            extension in THUMBNAIL_EXTENSIONS -> thumbnailState(name, request)
            extension in AUDIO_EXTENSIONS -> iconState(R.drawable.ic_file_music, request)
            extension in ARCHIVE_EXTENSIONS -> iconState(IoR.drawable.ic_archive_24, request)
            extension == "apk" -> iconState(IoR.drawable.ic_package_variant_24, request)
            else -> iconState(R.drawable.ic_file, request)
        }
    }

    /**
     * The app's own type icon, mirroring what `PathPreviewFetcher` falls back to: the XML tint is
     * stripped and [DataSource.MEMORY] lets `TintedAsyncImage` apply the theme tint.
     */
    private fun iconState(iconRes: Int, request: ImageRequest): AsyncImagePainter.State {
        val drawable = AppCompatResources.getDrawable(request.context, iconRes)!!
        drawable.setTintList(null)
        return successState(drawable.asImage(), request, DataSource.MEMORY)
    }

    /** A stand-in photo/video preview. [DataSource.DISK] keeps it out of the type-icon tinting. */
    private fun thumbnailState(name: String, request: ImageRequest): AsyncImagePainter.State {
        val hue = hueOf(name)
        val bitmap = createBitmap(THUMBNAIL_EDGE, THUMBNAIL_EDGE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                THUMBNAIL_EDGE.toFloat(),
                THUMBNAIL_EDGE.toFloat(),
                Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.92f)),
                Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.70f, 0.55f)),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, THUMBNAIL_EDGE.toFloat(), THUMBNAIL_EDGE.toFloat(), paint)

        val horizon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(80, floatArrayOf((hue + 180f) % 360f, 0.35f, 0.98f))
        }
        canvas.drawRect(
            0f,
            THUMBNAIL_EDGE * 0.62f,
            THUMBNAIL_EDGE.toFloat(),
            THUMBNAIL_EDGE.toFloat(),
            horizon,
        )

        return successState(bitmap.asImage(), request, DataSource.DISK)
    }

    /** A stand-in launcher icon: a coloured tile with the package's initial. */
    private fun appIconState(packageName: String, request: ImageRequest): AsyncImagePainter.State {
        val hue = hueOf(packageName)
        val bitmap = createBitmap(ICON_EDGE, ICON_EDGE)
        val canvas = Canvas(bitmap)
        val edge = ICON_EDGE.toFloat()

        val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
        }
        canvas.drawRoundRect(RectF(0f, 0f, edge, edge), edge * 0.22f, edge * 0.22f, tile)

        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = edge * 0.56f
            typeface = Typeface.DEFAULT_BOLD
        }
        val letter = packageName.substringAfterLast('.').take(1).uppercase().ifEmpty { "?" }
        val baseline = edge / 2f - (glyph.descent() + glyph.ascent()) / 2f
        canvas.drawText(letter, edge / 2f, baseline, glyph)

        return successState(bitmap.asImage(), request, DataSource.DISK)
    }

    /**
     * Built by hand instead of via the `AsyncImagePreviewHandler { }` factory: that one always
     * reports [DataSource.MEMORY], which would make `TintedAsyncImage` flatten the coloured
     * stand-ins to a single tint colour.
     */
    private fun successState(
        image: CoilImage,
        request: ImageRequest,
        dataSource: DataSource,
    ): AsyncImagePainter.State.Success = AsyncImagePainter.State.Success(
        painter = image.asPainter(request.context),
        result = SuccessResult(
            image = image,
            request = request,
            dataSource = dataSource,
        ),
    )

    // Fixed sizes: resolving the request's size resolver here can suspend forever, a preview render
    // has no layout pass to feed it.
    private fun createBitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** [String.hashCode] is contractually stable, so the same name always yields the same colour. */
    private fun hueOf(source: String): Float = source.hashCode().mod(360).toFloat()

    private const val ICON_EDGE = 128
    private const val THUMBNAIL_EDGE = 256

    private val THUMBNAIL_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp",
        "mp4", "mkv", "webm", "mov", "avi", "3gp",
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz")
}

/** [PreviewWrapper] plus the image stand-ins. Exactly one per render, never nested. */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun ScreenshotPreviewWrapper(content: @Composable () -> Unit) = PreviewWrapper {
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides ScreenshotImagePreviewHandler) {
        content()
    }
}
