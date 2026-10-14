package eu.darken.butler.viewer.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.viewer.core.TextPreview
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableState
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.math.sign

/**
 * Swipe-to-step on the production page, with its chrome-toggle tap detectors installed. The pager
 * cases host the page on the middle page of a parent [HorizontalPager], as the tab pager does.
 *
 * The page is 400 px wide (mdpi), so the 200 px swipes below commit by distance as well as by speed.
 */
@Config(qualifiers = "w400dp-h800dp")
class ViewerSwipeStepTest : ComposeTest() {

    private val a = LocalPath.build("/storage/emulated/0/DCIM/a.jpg")
    private val b = LocalPath.build("/storage/emulated/0/DCIM/b.jpg")
    private val c = LocalPath.build("/storage/emulated/0/DCIM/c.jpg")

    private val image = ViewerContent.Image(MimeInfo("image/jpeg"))
    private val text = ViewerContent.Text(MimeInfo("text/plain"))

    // Larger than the pane at twice the fitted zoom, so a zoomed page has room to pan sideways.
    private val bitmap = Bitmap.createBitmap(1600, 1600, Bitmap.Config.ARGB_8888)

    private val workspaceId = Workspace.Id()
    private val actions = mutableListOf<ViewerActionBarItem>()
    private val pdfPageSteps = mutableListOf<Int>()
    private val pageState = mutableStateOf(ready())
    private lateinit var zoomableState: ZoomableState
    private lateinit var scope: CoroutineScope
    private lateinit var pagerState: PagerState
    private var touchSlop = 0f

    private fun ready(
        current: LocalPath = b,
        content: ViewerContent = image,
        previous: LocalPath? = a,
        next: LocalPath? = c,
        listing: Boolean = true,
        pdfPage: ViewerWorkspaceViewModel.PdfPage? = null,
        lines: List<String>? = null,
    ) = ViewerWorkspaceViewModel.State.Ready(
        content = content,
        fileInfo = ViewerFileInfo(size = 1024L),
        source = ViewerSource.Stored(current),
        imageSource = BitmapZoomableImageSource(bitmap).takeIf { content is ViewerContent.Image },
        pdfPage = pdfPage,
        textPreview = lines?.let {
            ViewerWorkspaceViewModel.TextPreviewResult(
                source = ViewerSource.Stored(current),
                preview = TextPreview(lines = it, charset = Charsets.UTF_8),
            )
        },
        neighbours = if (listing) ViewerNeighbours(current = current, previous = previous, next = next) else null,
    )

    private fun setPage(
        inPager: Boolean = false,
        swipeToStepEnabled: Boolean = true,
        edgeStripsPx: Pair<Int, Int> = 0 to 0,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        unfocusedPane: Boolean = false,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocused provides true,
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    zoomableState = rememberViewerZoomableState()
                    scope = rememberCoroutineScope()
                    val page: @Composable () -> Unit = {
                        ViewerWorkspacePage(
                            workspaceId = workspaceId,
                            // Split-pane layout: keeps the mascot-bearing workspace button, which
                            // Robolectric cannot rasterise, out of the toolbar cutout.
                            design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                            state = pageState.value,
                            zoomableState = zoomableState,
                            swipeToStepEnabled = swipeToStepEnabled,
                            swipeStepEdgeStripsPx = edgeStripsPx,
                            onAction = { actions.add(it) },
                            onPdfPreviousPage = { pdfPageSteps.add(-1) },
                            onPdfNextPage = { pdfPageSteps.add(1) },
                        )
                    }
                    when {
                        inPager -> {
                            pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                                if (index == 1) page() else Box(modifier = Modifier.fillMaxSize())
                            }
                        }

                        // An unfocused pane whose click-to-focus gate swallows the first press.
                        unfocusedPane -> CompositionLocalProvider(LocalWorkspaceFocusRequest provides {}) {
                            PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) { page() }
                        }

                        else -> page()
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** A quick swipe across the middle of the page, starting at [fromX] of the page's width. */
    private fun swipeBy(dx: Float, dy: Float = 0f, fromX: (Float) -> Float = { it / 2f }) {
        composeTestRule.onRoot().performTouchInput {
            touchSlop = viewConfiguration.touchSlop
            val start = Offset(fromX(width.toFloat()), centerY)
            swipe(start = start, end = start + Offset(dx, dy), durationMillis = 200L)
        }
        composeTestRule.waitForIdle()
    }

    /**
     * A finger down in the middle, moved by [dx] and held there. The first move stays under the touch
     * slop and the next one jumps well past it, so whichever detector claims does so unambiguously.
     */
    private fun pressAndDrag(dx: Float) {
        composeTestRule.onRoot().performTouchInput {
            touchSlop = viewConfiguration.touchSlop
            down(center)
            val first = sign(dx) * touchSlop / 2f
            moveBy(Offset(first, 0f))
            repeat(3) { moveBy(Offset((dx - first) / 3f, 0f)) }
        }
        composeTestRule.waitForIdle()
    }

    /** Lifts after the finger rested long enough that the release carries no fling. */
    private fun liftWithoutFling() {
        composeTestRule.onRoot().performTouchInput {
            advanceEventTime(500L)
            up()
        }
        composeTestRule.waitForIdle()
    }

    /** Past the arrival timeout of a step nothing answered, and past the return to rest after it. */
    private fun outlastRefusedStep() {
        composeTestRule.mainClock.advanceTimeBy(SWIPE_STEP_ARRIVAL_TIMEOUT.inWholeMilliseconds + 2_000L)
        composeTestRule.waitForIdle()
    }

    private val contentOffsetX: Float
        get() = composeTestRule.onNodeWithTag(VIEWER_SWIPE_CONTENT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .positionInRoot.x

    private fun pagerAtRestOn(page: Int) {
        pagerState.settledPage shouldBe page
        pagerState.currentPageOffsetFraction shouldBe 0f
    }

    private val zoomedIn: Boolean
        get() {
            val transformation = zoomableState.contentTransformation
            return isZoomedIn(
                transformationSpecified = transformation.isSpecified,
                userZoom = if (transformation.isSpecified) transformation.scaleMetadata.userZoom else 1f,
            )
        }

    private fun zoomIn() {
        scope.launch { zoomableState.zoomBy(zoomFactor = 2f, animationSpec = snap()) }
        composeTestRule.waitForIdle()
        zoomedIn shouldBe true
    }

    /** Pans as far as it goes toward +x, so a further drag toward +x has nothing left to pan. */
    private fun panToEdge() {
        scope.launch { zoomableState.panBy(offset = Offset(4_000f, 0f), animationSpec = snap()) }
        composeTestRule.waitForIdle()
    }

    private val next = ViewerActionBarItem.NextFile(isEnabled = true)
    private val previous = ViewerActionBarItem.PreviousFile(isEnabled = true)

    // (a) Each content type steps both ways, its own tap detector consuming the down notwithstanding.

    @Test
    fun `swiping left on a picture steps to the next file`() {
        setPage()

        swipeBy(dx = -200f)

        actions shouldBe listOf(next)
    }

    @Test
    fun `swiping right on a picture steps to the previous file`() {
        setPage()

        swipeBy(dx = 200f)

        actions shouldBe listOf(previous)
    }

    @Test
    fun `swiping the placeholder of an unsupported file steps both ways`() {
        pageState.value = ready(content = ViewerContent.Unsupported(MimeInfo("application/x-thing")))
        setPage()

        swipeBy(dx = -200f)
        outlastRefusedStep()
        swipeBy(dx = 200f)

        actions shouldBe listOf(next, previous)
    }

    @Test
    fun `swiping a text line that fits steps both ways`() {
        pageState.value = ready(content = text, lines = listOf(SHORT_LINE))
        setPage()

        val line = composeTestRule.onNodeWithText(SHORT_LINE)
        line.performTouchInput { swipe(start = center, end = center + Offset(-200f, 0f), durationMillis = 200L) }
        composeTestRule.waitForIdle()
        outlastRefusedStep()
        line.performTouchInput { swipe(start = center, end = center + Offset(200f, 0f), durationMillis = 200L) }
        composeTestRule.waitForIdle()

        actions shouldBe listOf(next, previous)
    }

    @Test
    fun `in a right-to-left layout swiping left steps to the previous file`() {
        setPage(layoutDirection = LayoutDirection.Rtl)

        swipeBy(dx = -200f)

        actions shouldBe listOf(previous)
    }

    // (b) A steppable viewer keeps horizontal drags from the tab pager.

    @Test
    fun `a swipe that steps leaves the tab pager where it is`() {
        setPage(inPager = true)

        swipeBy(dx = -200f)

        actions shouldBe listOf(next)
        pagerAtRestOn(1)
    }

    @Test
    fun `a swipe at the last file moves neither the tab pager nor the file`() {
        pageState.value = ready(next = null)
        setPage(inPager = true)

        swipeBy(dx = -200f)

        actions shouldBe emptyList()
        pagerAtRestOn(1)
        contentOffsetX shouldBe 0f
    }

    @Test
    fun `a steep diagonal drag does not reach the tab pager`() {
        setPage(inPager = true)

        composeTestRule.onRoot().performTouchInput {
            val slop = viewConfiguration.touchSlop
            swipe(start = center, end = center + Offset(-2f * slop, -3f * slop), durationMillis = 200L)
        }
        composeTestRule.waitForIdle()

        pagerAtRestOn(1)
    }

    // (c) A zoomed surface pans, and at its pan edge swallows the drag.

    @Test
    fun `a drag on a zoomed picture pans it instead of stepping`() {
        setPage(inPager = true)
        zoomIn()
        val before = zoomableState.contentTransformation.offset

        pressAndDrag(dx = -80f)

        zoomableState.contentTransformation.offset shouldNotBe before
        contentOffsetX shouldBe 0f
        pagerState.currentPageOffsetFraction shouldBe 0f
        liftWithoutFling()
        actions shouldBe emptyList()
        pagerAtRestOn(1)
    }

    @Test
    fun `a drag past the pan edge of a zoomed picture moves nothing`() {
        setPage(inPager = true)
        zoomIn()
        panToEdge()
        val before = zoomableState.contentTransformation.offset

        pressAndDrag(dx = 120f)

        zoomableState.contentTransformation.offset shouldBe before
        contentOffsetX shouldBe 0f
        pagerState.currentPageOffsetFraction shouldBe 0f
        liftWithoutFling()
        actions shouldBe emptyList()
        pagerAtRestOn(1)
    }

    @Test
    fun `a fling past the pan edge of a zoomed picture does not step either`() {
        setPage(inPager = true)
        zoomIn()
        panToEdge()

        swipeBy(dx = 200f)

        actions shouldBe emptyList()
        pagerAtRestOn(1)
        contentOffsetX shouldBe 0f
    }

    @Test
    fun `a drag on a zoomed PDF page pans it and keeps the page`() {
        pageState.value = ready(
            content = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3),
            pdfPage = ViewerWorkspaceViewModel.PdfPage(index = 1, bitmap = bitmap),
        )
        setPage(inPager = true)
        zoomIn()
        val before = zoomableState.contentTransformation.offset

        pressAndDrag(dx = -80f)

        zoomableState.contentTransformation.offset shouldNotBe before
        contentOffsetX shouldBe 0f
        liftWithoutFling()
        actions shouldBe emptyList()
        pdfPageSteps shouldBe emptyList()
        pagerAtRestOn(1)
    }

    @Test
    fun `a drag past the pan edge of a zoomed PDF page moves nothing and keeps the page`() {
        pageState.value = ready(
            content = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3),
            pdfPage = ViewerWorkspaceViewModel.PdfPage(index = 1, bitmap = bitmap),
        )
        setPage(inPager = true)
        zoomIn()
        panToEdge()
        val before = zoomableState.contentTransformation.offset

        pressAndDrag(dx = 120f)

        zoomableState.contentTransformation.offset shouldBe before
        contentOffsetX shouldBe 0f
        liftWithoutFling()
        swipeBy(dx = 200f)
        actions shouldBe emptyList()
        pdfPageSteps shouldBe emptyList()
        pagerAtRestOn(1)
    }

    private fun staleZoomStillSteps(content: ViewerContent, lines: List<String>? = null) {
        setPage()
        zoomIn()

        pageState.value = ready(content = content, lines = lines)
        composeTestRule.waitForIdle()
        // The premise: telephoto still reports the zoom of the picture that is gone.
        zoomedIn shouldBe true

        swipeBy(dx = -200f)

        actions shouldBe listOf(next)
    }

    @Test
    fun `a zoom left behind by a picture does not block stepping while loading`() =
        staleZoomStillSteps(ViewerContent.Loading)

    @Test
    fun `a zoom left behind by a picture does not block stepping from a failure`() =
        staleZoomStillSteps(ViewerContent.Failed(IllegalStateException("Decoder gave up")))

    @Test
    fun `a zoom left behind by a picture does not block stepping through text`() =
        staleZoomStillSteps(text, lines = listOf(SHORT_LINE))

    // (d) Without a listing, or with swiping off, a swipe goes where it always did: on a picture to
    // the tab pager, on a text line to the line's own scroller, which keeps it in this harness.

    @Test
    fun `without a listing a swipe on a picture turns the tab pager`() {
        pageState.value = ready(listing = false)
        setPage(inPager = true)

        swipeBy(dx = -200f)

        actions shouldBe emptyList()
        pagerAtRestOn(2)
    }

    @Test
    fun `with swiping off a swipe on a picture turns the tab pager`() {
        setPage(inPager = true, swipeToStepEnabled = false)

        swipeBy(dx = -200f)

        actions shouldBe emptyList()
        pagerAtRestOn(2)
    }

    private fun swipeTextLine(line: String, dx: Float, listing: Boolean, swipeToStepEnabled: Boolean) {
        pageState.value = ready(content = text, listing = listing, lines = listOf(SHORT_LINE, LONG_LINE))
        setPage(inPager = true, swipeToStepEnabled = swipeToStepEnabled)
        val before = contentOffsetX

        composeTestRule.onNodeWithText(line).performTouchInput {
            swipe(start = center, end = center + Offset(dx, 0f), durationMillis = 200L)
        }
        composeTestRule.waitForIdle()

        actions shouldBe emptyList()
        contentOffsetX shouldBe before
    }

    @Test
    fun `without a listing a swipe on a text line that fits is left to the line as before`() {
        swipeTextLine(SHORT_LINE, dx = -200f, listing = false, swipeToStepEnabled = true)
        pagerAtRestOn(1)
    }

    @Test
    fun `without a listing a swipe past the start of a long text line is left to the line as before`() {
        swipeTextLine(LONG_LINE, dx = 200f, listing = false, swipeToStepEnabled = true)
        pagerAtRestOn(1)
    }

    @Test
    fun `with swiping off a swipe on a text line that fits is left to the line as before`() {
        swipeTextLine(SHORT_LINE, dx = -200f, listing = true, swipeToStepEnabled = false)
        pagerAtRestOn(1)
    }

    @Test
    fun `with swiping off a swipe past the start of a long text line is left to the line as before`() {
        swipeTextLine(LONG_LINE, dx = 200f, listing = true, swipeToStepEnabled = false)
        pagerAtRestOn(1)
    }

    // (e) Drags the gesture leaves alone.

    @Test
    fun `a drag from the left back-gesture strip is left to the tab pager`() {
        setPage(inPager = true, edgeStripsPx = 60 to 60)

        swipeBy(dx = 200f, fromX = { 5f })

        actions shouldBe emptyList()
        pagerAtRestOn(0)
    }

    @Test
    fun `a drag from the right back-gesture strip is left to the tab pager`() {
        setPage(inPager = true, edgeStripsPx = 60 to 60)

        swipeBy(dx = -200f, fromX = { it - 5f })

        actions shouldBe emptyList()
        pagerAtRestOn(2)
    }

    @Test
    fun `a press the pane's focus gate swallows starts no swipe`() {
        setPage(unfocusedPane = true)

        pressAndDrag(dx = -80f)
        contentOffsetX shouldBe 0f
        liftWithoutFling()
        swipeBy(dx = -200f)

        actions shouldBe emptyList()
        contentOffsetX shouldBe 0f
    }

    // (f) The content follows the finger, and a step is followed by identity.

    @Test
    fun `the content follows the finger and springs back from a short release`() {
        setPage()

        pressAndDrag(dx = -80f)

        contentOffsetX shouldBe (-(80f - touchSlop) plusOrMinus 1f)
        liftWithoutFling()
        contentOffsetX shouldBe 0f
        actions shouldBe emptyList()
    }

    @Test
    fun `toward a side with no file the content follows the finger only partly and springs back`() {
        pageState.value = ready(next = null)
        setPage()

        pressAndDrag(dx = -80f)

        contentOffsetX shouldBeLessThan 0f
        contentOffsetX shouldBeGreaterThan -(80f - touchSlop) / 2f
        liftWithoutFling()
        contentOffsetX shouldBe 0f
        actions shouldBe emptyList()
    }

    @Test
    fun `a step nothing answers brings the content back after the timeout`() {
        setPage()
        composeTestRule.mainClock.autoAdvance = false

        swipeBy(dx = -200f)
        composeTestRule.mainClock.advanceTimeBy(600L)

        actions shouldBe listOf(next)
        contentOffsetX shouldBeLessThan -390f

        composeTestRule.mainClock.advanceTimeBy(600L)
        contentOffsetX shouldBeLessThan -390f

        outlastRefusedStep()
        contentOffsetX shouldBe 0f
        actions shouldBe listOf(next)
    }

    @Test
    fun `a step that lands slides its file in once loaded and dispatches once`() {
        setPage()
        composeTestRule.mainClock.autoAdvance = false

        swipeBy(dx = -200f)
        composeTestRule.mainClock.advanceTimeBy(600L)
        actions shouldBe listOf(next)
        contentOffsetX shouldBeLessThan -390f

        // The tab is replaced under the same page; the new viewer starts out loading.
        pageState.value = ready(current = c, content = ViewerContent.Loading, previous = b, next = null)
        composeTestRule.mainClock.advanceTimeBy(400L)
        contentOffsetX shouldBeLessThan -390f

        pageState.value = ready(current = c, previous = b, next = null)
        composeTestRule.mainClock.advanceTimeBy(100L)
        // Entering from the side opposite the one it left by.
        contentOffsetX shouldBeGreaterThan 0f
        contentOffsetX shouldBeLessThan 400f

        composeTestRule.mainClock.advanceTimeBy(1_500L)
        contentOffsetX shouldBe 0f
        actions shouldBe listOf(next)
    }

    @Test
    fun `another file landing mid-step returns the content to rest for good`() {
        setPage()
        composeTestRule.mainClock.autoAdvance = false

        swipeBy(dx = -200f)
        composeTestRule.mainClock.advanceTimeBy(600L)
        actions shouldBe listOf(next)
        contentOffsetX shouldBeLessThan -390f

        // What a keyboard arrow stepping back would land on before the swipe's own step.
        pageState.value = ready(current = a, previous = null, next = b)
        composeTestRule.mainClock.advanceTimeBy(1_000L)
        contentOffsetX shouldBe 0f

        // The file the swipe asked for turns up after all; it must not animate anything.
        pageState.value = ready(current = c, previous = b, next = null)
        repeat(10) {
            composeTestRule.mainClock.advanceTimeByFrame()
            contentOffsetX shouldBe 0f
        }
        actions shouldBe listOf(next)
    }

    // (g) A second finger.

    @Test
    fun `a second finger before the claim is left to the zoom`() {
        setPage()

        composeTestRule.onRoot().performTouchInput {
            down(0, center)
            down(1, center + Offset(60f, 0f))
            repeat(3) {
                moveBy(0, Offset(-40f, 0f))
                moveBy(1, Offset(40f, 0f))
            }
            up(1)
            up(0)
        }
        composeTestRule.waitForIdle()

        actions shouldBe emptyList()
        contentOffsetX shouldBe 0f
    }

    /** The rest of the gesture stays swallowed too: an unconsumed move would let the pager take it. */
    @Test
    fun `a second finger after the claim drops the drag and the content springs back`() {
        setPage(inPager = true)

        pressAndDrag(dx = -80f)
        contentOffsetX shouldBeLessThan 0f

        composeTestRule.onRoot().performTouchInput { down(1, center + Offset(0f, 100f)) }
        composeTestRule.waitForIdle()
        contentOffsetX shouldBe 0f

        composeTestRule.onRoot().performTouchInput {
            repeat(4) {
                moveBy(0, Offset(-50f, 0f))
                moveBy(1, Offset(-50f, 0f))
            }
        }
        composeTestRule.waitForIdle()
        contentOffsetX shouldBe 0f
        pagerState.currentPageOffsetFraction shouldBe 0f

        composeTestRule.onRoot().performTouchInput {
            up(1)
            up(0)
        }
        composeTestRule.waitForIdle()
        actions shouldBe emptyList()
        pagerAtRestOn(1)
        contentOffsetX shouldBe 0f
    }

    companion object {
        private const val SHORT_LINE = "short line"

        // Robolectric measures about a pixel per character, so this overflows the 400 px pane.
        private val LONG_LINE = "x".repeat(2_000)
    }
}
