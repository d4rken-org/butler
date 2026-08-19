package eu.darken.butler.viewer.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.RememberObserver
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.SAFPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Telephoto's `ZoomableImage` remembers the painter it gets from a `PainterDelegate`, and that
 * remember is the only thing that makes a drawable-backed painter visible and starts its animation.
 * So the painter we hand over has to be the one that draws - not a `rememberAsyncImagePainter`
 * wrapper that consumes the remember slot for itself.
 *
 * Rendering itself cannot be asserted here: Robolectric stubs the image decoders and never draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayPainterFallbackTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val gifPath: APath<*> = SAFPath.build(
        "content://com.example.provider/tree/primary",
        "animated.gif",
    )

    /** Stands in for the `AnimatedImageDrawable`/`MovieDrawable` a GIF decodes to. */
    private class FakeAnimatedDrawable : Drawable(), Animatable {
        private var running = false

        override fun getIntrinsicWidth(): Int = 120
        override fun getIntrinsicHeight(): Int = 80
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE

        override fun start() {
            running = true
        }

        override fun stop() {
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    private fun sourceFor(imageLoader: ImageLoader) = GatewayZoomableImageSource(
        context = context,
        contentReader = readerFor(mockk<GatewaySwitch>(relaxed = true)),
        imageLoader = imageLoader,
        dispatcherProvider = TestDispatcherProvider(),
        source = ViewerSource.Stored(gifPath),
        onError = {},
    )

    private fun loaderReturning(result: (ImageRequest) -> ImageResult) = mockk<ImageLoader>().apply {
        coEvery { execute(any()) } answers { result(firstArg<ImageRequest>()) }
    }

    private fun loaderFor(drawable: Drawable) = loaderReturning { request ->
        SuccessResult(image = drawable.asImage(), request = request)
    }

    private fun renderedPainter(drawable: Drawable) = runBlocking { sourceFor(loaderFor(drawable)).loadPainter() }
        .shouldBeInstanceOf<GatewayZoomableImageSource.Resolution.Rendered>()
        .painter

    @Test
    fun `a drawable result is handed to telephoto as the painter that draws it`() {
        val painter = renderedPainter(FakeAnimatedDrawable())

        // An AsyncImagePainter would swallow telephoto's remember slot and leave the drawable
        // painter underneath it without the callbacks it needs to draw anything.
        painter.shouldNotBeInstanceOf<AsyncImagePainter>()
        painter.shouldBeInstanceOf<RememberObserver>()
    }

    @Test
    fun `remembering the painter telephoto receives starts the drawable`() {
        val drawable = FakeAnimatedDrawable()
        val painter = renderedPainter(drawable).shouldBeInstanceOf<RememberObserver>()

        drawable.isRunning shouldBe false

        painter.onRemembered()
        drawable.isRunning shouldBe true

        painter.onForgotten()
        drawable.isRunning shouldBe false
    }

    @Test
    fun `the delegate reports the image size telephoto zooms against`() {
        renderedPainter(FakeAnimatedDrawable()).intrinsicSize shouldBe Size(120f, 80f)
    }

    @Test
    fun `a failed load is reported instead of leaving an empty canvas`() {
        val boom = IllegalStateException("decoder gave up")
        val loader = loaderReturning { request ->
            ErrorResult(image = null, request = request, throwable = boom)
        }
        var reported: Throwable? = null
        val source = GatewayZoomableImageSource(
            context = context,
            contentReader = readerFor(mockk<GatewaySwitch>(relaxed = true)),
            imageLoader = loader,
            dispatcherProvider = TestDispatcherProvider(),
            source = ViewerSource.Stored(gifPath),
            onError = { reported = it },
        )

        val resolution = runBlocking { source.loadPainter() }

        resolution shouldBe GatewayZoomableImageSource.Resolution.Failed
        reported shouldBe boom
    }
}
