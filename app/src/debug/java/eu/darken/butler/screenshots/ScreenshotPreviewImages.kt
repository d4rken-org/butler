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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
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
 * Everything it produces is derived from the request data and [iconTintArgb] alone, so a render is
 * reproducible.
 *
 * @param iconTintArgb the colour type icons are baked with. `TintedAsyncImage` cannot do it here:
 *   it reads the painter state once at first composition, when the request has not been answered
 *   yet, and layoutlib never recomposes it to pick the tint up.
 */
@OptIn(ExperimentalCoilApi::class)
internal class ScreenshotImagePreviewHandler(
    private val iconTintArgb: Int,
) : AsyncImagePreviewHandler {

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
            // Production renders the first page here (PdfPreviewGenerator), never a type icon.
            extension == "pdf" -> documentPreviewState(request)
            extension in AUDIO_EXTENSIONS -> iconState(R.drawable.ic_file_music, request)
            extension in ARCHIVE_EXTENSIONS -> iconState(IoR.drawable.ic_archive_24, request)
            extension == "apk" -> iconState(IoR.drawable.ic_package_variant_24, request)
            // Everything else, code and text included, is what PathPreviewFetcher falls back to.
            else -> iconState(R.drawable.ic_file, request)
        }
    }

    /**
     * The app's own type icon, rasterized with the tint already baked in — see [iconTintArgb] for
     * why the tint cannot be left to `TintedAsyncImage`.
     */
    private fun iconState(iconRes: Int, request: ImageRequest): AsyncImagePainter.State {
        val drawable = AppCompatResources.getDrawable(request.context, iconRes)!!.mutate()
        drawable.setTint(iconTintArgb)
        drawable.setBounds(0, 0, ICON_EDGE, ICON_EDGE)

        val bitmap = createBitmap(ICON_EDGE, ICON_EDGE)
        drawable.draw(Canvas(bitmap))

        return successState(bitmap.asImage(), request, DataSource.DISK)
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

    /** A stand-in first-page render: a portrait page with a heading and a few lines of body text. */
    private fun documentPreviewState(request: ImageRequest): AsyncImagePainter.State {
        val bitmap = createBitmap(PAGE_WIDTH, PAGE_HEIGHT)
        val canvas = Canvas(bitmap)
        val width = PAGE_WIDTH.toFloat()
        val height = PAGE_HEIGHT.toFloat()

        val page = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 249, 246) }
        canvas.drawRect(0f, 0f, width, height, page)

        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(206, 204, 198)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, edge)

        val margin = width * 0.14f
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(74, 72, 68) }
        canvas.drawRect(margin, height * 0.12f, width - margin * 2.2f, height * 0.17f, heading)

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(158, 156, 150) }
        // Ragged right edge, so it reads as text rather than as a bar chart.
        val lineWidths = listOf(1f, 0.94f, 0.98f, 0.72f, 1f, 0.9f, 0.55f)
        lineWidths.forEachIndexed { index, fraction ->
            val top = height * (0.26f + index * 0.085f)
            canvas.drawRect(margin, top, margin + (width - margin * 2) * fraction, top + height * 0.032f, body)
        }

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
     * reports [DataSource.MEMORY], and `TintedAsyncImage` would flatten anything it does manage to
     * observe as such to a single tint colour.
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

    private companion object {
        const val ICON_EDGE = 128
        const val THUMBNAIL_EDGE = 256
        const val PAGE_WIDTH = 181
        const val PAGE_HEIGHT = 256

        val THUMBNAIL_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp",
            "mp4", "mkv", "webm", "mov", "avi", "3gp",
        )
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
        val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz")
    }
}

/**
 * [PreviewWrapper] plus the image stand-ins. Exactly one per render, never nested.
 *
 * The handler is remembered per tint rather than held in a singleton: screenshot renders can
 * overlap, and a mutable shared handler would let one render's theme colour leak into another's.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun ScreenshotPreviewWrapper(content: @Composable () -> Unit) = PreviewWrapper {
    val tintArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val handler = remember(tintArgb) { ScreenshotImagePreviewHandler(tintArgb) }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides handler) {
        content()
    }
}
