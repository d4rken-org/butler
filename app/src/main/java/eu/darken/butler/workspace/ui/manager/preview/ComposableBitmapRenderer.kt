package eu.darken.butler.workspace.ui.manager.preview

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import android.view.Surface
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Based on:
 * https://gist.github.com/iamcalledrob/871568679ad58e64959b097d4ef30738
 * https://gist.github.com/jamesjmtaylor/40e038e04c785ffa0bea71a517b34831
 * https://gist.github.com/riggaroo/0e0072b3e85aa91443659031925fa47c
 */
@Singleton
class ComposableBitmapRenderer @Inject constructor(private val appContext: Context) {

    private val displayService by lazy {
        appContext.getSystemService(DisplayManager::class.java)
    }

    /**
     * Limits concurrent bitmap renders to prevent resource contention.
     * VirtualDisplay and Presentation creation are expensive operations that
     * cause significant lag when executed in parallel (e.g., tab manager with 6-8 workspaces).
     */
    private val renderSemaphore = Semaphore(1)

    suspend fun renderToBitmap(
        canvasSize: DpSize,
        captureDelay: Duration = 300.milliseconds,
        captureContext: Context? = appContext,
        viewModelStoreOwner: ViewModelStoreOwner? = TemporaryViewModelStoreOwner(),
        composableContent: @Composable () -> Unit,
    ): Bitmap? {
        log(TAG) { "renderToBitmap($canvasSize, $captureDelay, $captureContext, $viewModelStoreOwner)" }
        val context = captureContext ?: appContext
        val deviceDensity = Density(
            density = context.resources.displayMetrics.density,
            fontScale = context.resources.configuration.fontScale
        )
        log(TAG) { "Using device density: ${deviceDensity.density}" }

        return renderSemaphore.withPermit {
            currentCoroutineContext().ensureActive()
            log(TAG) { "renderToBitmap: Acquired render permit for $canvasSize" }
            useVirtualDisplay { display ->
                captureComposable(
                    captureContext = context,
                    size = canvasSize,
                    density = deviceDensity,
                    display = display,
                    viewModelStoreOwner = viewModelStoreOwner,
                ) {
                    LaunchedEffect(Unit) {
                        delay(captureDelay)
                        capture()
                    }
                    composableContent()
                }
            }
        }
    }

    private suspend fun <T> useVirtualDisplay(callback: suspend (display: Display) -> T): T? {
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val outerContext = appContext.resources.displayMetrics
        val virtualDisplay: VirtualDisplay? = displayService.createVirtualDisplay(
            "virtualDisplay",
            outerContext.widthPixels,
            outerContext.heightPixels,
            outerContext.densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        if (virtualDisplay == null) throw IllegalStateException("Virtual display is unavaialble")

        return try {
            callback(virtualDisplay.display)
        } finally {
            virtualDisplay.release()
            surface.release()
            texture.release()
        }
    }

    private data class CaptureComposableScope(val capture: () -> Unit)

    private fun Size.roundedToIntSize(): IntSize = IntSize(width.toInt(), height.toInt())

    private class EmptySavedStateRegistryOwner : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this).apply {
            performRestore(null)
        }

        private val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get()

        override val lifecycle: Lifecycle
            get() = object : Lifecycle() {
                @Suppress("UNNECESSARY_SAFE_CALL")
                override fun addObserver(observer: LifecycleObserver) {
                    lifecycleOwner?.lifecycle?.addObserver(observer)
                }

                @Suppress("UNNECESSARY_SAFE_CALL")
                override fun removeObserver(observer: LifecycleObserver) {
                    lifecycleOwner?.lifecycle?.removeObserver(observer)
                }

                override val currentState = State.INITIALIZED
            }

        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry
    }

    private class TemporaryViewModelStoreOwner : ViewModelStoreOwner {
        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore get() = store
    }

    /** Captures composable content, by default using a hidden window on the default display.
     *
     *  Be sure to invoke capture() within the composable content (e.g. in a LaunchedEffect) to perform the capture.
     *  This gives some level of control over when the capture occurs, so it's possible to wait for async resources */
    private suspend fun captureComposable(
        size: DpSize,
        density: Density = Density(density = 1f),
        display: Display,
        captureContext: Context,
        viewModelStoreOwner: ViewModelStoreOwner?,
        content: @Composable CaptureComposableScope.() -> Unit,
    ): Bitmap {
        val presentation = Presentation(
            captureContext,
            display
        ).apply {
            window?.decorView?.apply {
                setViewTreeLifecycleOwner(ProcessLifecycleOwner.get())
                setViewTreeSavedStateRegistryOwner(EmptySavedStateRegistryOwner())
                setViewTreeViewModelStoreOwner(viewModelStoreOwner)
                alpha = 0f // If using default display, to ensure this does not appear on top of content.
            }
        }

        val composeView = ComposeView(captureContext).apply {
            val intSize = with(density) { size.toSize().roundedToIntSize() }
            require(intSize.width > 0 && intSize.height > 0) { "pixel size must not have zero dimension" }
            log(TAG) { "Rendering composable to ${intSize.width}x${intSize.height}" }
            layoutParams = ViewGroup.LayoutParams(intSize.width, intSize.height)
        }
        presentation.setContentView(composeView, composeView.layoutParams)
        presentation.show()

        return try {
            suspendCancellableCoroutine { continuation ->
                composeView.setContent {
                    val coroutineScope = rememberCoroutineScope()
                    val graphicsLayer = rememberGraphicsLayer()
                    Box(
                        modifier = Modifier
                            .size(size)
                            .drawWithContent {
                                graphicsLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(graphicsLayer)
                            },
                    ) {
                        CaptureComposableScope(
                            capture = {
                                coroutineScope.launch {
                                    val composeImageBitmap = graphicsLayer.toImageBitmap()
                                    continuation.resumeWith(Result.success(composeImageBitmap.asAndroidBitmap()))
                                }
                            },
                        ).content()
                    }
                }
            }
        } finally {
            presentation.dismiss()
        }
    }

    companion object {
        private val TAG = logTag("ComposableBitmapRenderer")
    }
}