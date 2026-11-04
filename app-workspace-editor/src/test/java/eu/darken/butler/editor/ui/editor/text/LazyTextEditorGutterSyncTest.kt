package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import io.kotest.matchers.shouldBe
import androidx.compose.ui.test.performSemanticsAction
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest
import kotlin.uuid.Uuid

/**
 * The line-number gutter must sit on its lines in the frame the content moves, not a frame later.
 *
 * A probe is re-laid out every frame and samples every row from its onGloballyPositioned callback,
 * i.e. at the end of that frame's layout pass, which is the state the frame draws. Reading through
 * the test rule instead would idle first and let catch-up work run before the read.
 * NATIVE graphics so long lines really wrap.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LazyTextEditorGutterSyncTest : ComposeTest() {

    private val engineEpoch = Uuid.random()

    /** Top of each row that is at least partly inside its column's viewport, by 0-based line. */
    private data class Frame(
        val gutterTops: Map<Int, Float>,
        val contentTops: Map<Int, Float>,
        val viewportBottom: Float,
    )

    private var probing by mutableStateOf(false)
    private val frames = mutableListOf<Frame>()
    private var lines by mutableStateOf(emptyList<String>())
    private var topPaddingPx by mutableFloatStateOf(0f)
    private var animatePadding by mutableStateOf(false)

    /** Resolves its top padding during measure, like the floating bars' content padding. */
    private val measuredPadding = object : PaddingValues {
        override fun calculateTopPadding(): Dp = topPaddingPx.dp
        override fun calculateBottomPadding(): Dp = 0.dp
        override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
        override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    }

    private fun setEditor(initial: List<String>, wordWrap: Boolean) {
        lines = initial
        composeTestRule.setContent {
            PreviewWrapper {
                Box(Modifier.fillMaxSize()) {
                    LaunchedEffect(animatePadding) {
                        // Like a bar collapse animation: the padding changes in every frame callback
                        if (animatePadding) repeat(10) { withFrameNanos { topPaddingPx += 7f } }
                    }
                    LazyTextEditor(
                        contentPadding = measuredPadding,
                        content = lines.joinToString("\n"),
                        totalLines = lines.size.toLong(),
                        cursorPosition = TextPosition(0L, 0L, 0),
                        selection = null,
                        windowToken = EditorEngine.DocumentToken(engineEpoch, 0L),
                        wordWrap = wordWrap,
                        onEnqueueDelta = {
                            CompletableDeferred(
                                EditorEngine.MutationResult.Applied(EditorEngine.DocumentToken(engineEpoch, 0L)),
                            )
                        },
                        onCursorPositionChange = {},
                        onSelectionChange = {},
                        onVisibleRangeChange = {},
                        onCursorMove = { _, _ -> },
                        onForwardDelete = {},
                    )
                    val root = LocalView.current as RootForTest
                    var tick by remember { mutableIntStateOf(0) }
                    LaunchedEffect(probing) {
                        if (probing) while (true) withFrameNanos { tick++ }
                    }
                    // Moves by a pixel every frame, so its global-position callback fires at the end of
                    // every frame's layout pass
                    Spacer(
                        Modifier
                            .offset { IntOffset(tick % 2, 0) }
                            .size(1.dp)
                            .onGloballyPositioned {
                                if (probing) frames += sample(root.semanticsOwner.unmergedRootSemanticsNode, lines)
                            },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun sample(root: SemanticsNode, lines: List<String>): Frame {
        val gutter = mutableMapOf<Int, Float>()
        val content = mutableMapOf<Int, Float>()
        var viewportBottom = 0f
        fun walk(node: SemanticsNode, inContent: Boolean) {
            val isList = node.config.getOrNull(SemanticsProperties.TestTag) == EDITOR_CONTENT_TEST_TAG
            if (isList) viewportBottom = node.boundsInRoot.bottom
            val here = inContent || isList
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            // Lazy lists keep scrolled-out items composed but unplaced, at stale positions.
            // boundsInRoot is clipped to the viewport (empty when scrolled out); positionInRoot is not.
            if (text != null && node.isPlacedInTree() && node.boundsInRoot.height > 0f) {
                if (here) {
                    lines.indexOf(text).takeIf { it >= 0 }?.let { content[it] = node.positionInRoot.y }
                } else {
                    text.toIntOrNull()?.let { gutter[it - 1] = node.positionInRoot.y }
                }
            }
            node.children.forEach { walk(it, here) }
        }
        walk(root, false)
        return Frame(gutter, content, viewportBottom)
    }

    private fun SemanticsNode.isPlacedInTree(): Boolean {
        var info: LayoutInfo? = layoutInfo
        while (info != null) {
            if (!info.isPlaced) return false
            info = info.parentInfo
        }
        return true
    }

    private fun startProbing() {
        composeTestRule.mainClock.autoAdvance = false
        probing = true
        // The probe's frame loop takes a few frames to start ticking
        repeat(10) {
            if (frames.isNotEmpty()) return
            composeTestRule.mainClock.advanceTimeByFrame()
        }
        frames.shouldNotBeEmpty()
    }

    private fun advanceAndCollectMisalignments(
        frameCount: Int,
        firstLines: MutableList<Int> = mutableListOf(),
    ): List<String> {
        val misaligned = mutableListOf<String>()
        repeat(frameCount) { index ->
            frames.clear()
            composeTestRule.mainClock.advanceTimeByFrame()
            frames.lastOrNull()?.contentTops?.keys?.minOrNull()?.let { firstLines += it }
            frames.forEach { frame ->
                val rows = frame.contentTops.keys.filter { it in frame.gutterTops }.sorted()
                // A row whose top is well inside the viewport must have its number shown
                val unnumbered = frame.contentTops
                    .filter { (line, top) -> top >= 0f && top < frame.viewportBottom - 40f && line !in frame.gutterTops }
                    .keys.sorted()
                if (unnumbered.isNotEmpty()) misaligned += "frame $index: no number for lines ${unnumbered.map { it + 1 }}"
                val off = rows.filter { frame.gutterTops[it] != frame.contentTops[it] }
                if (off.isNotEmpty()) {
                    val line = off.first()
                    misaligned += "frame $index: ${off.size}/${rows.size} rows off, line ${line + 1} " +
                        "gutter top ${frame.gutterTops[line]} vs content top ${frame.contentTops[line]}"
                }
            }
        }
        return misaligned
    }

    private fun scrollAndCheckEveryFrame(initial: List<String>, wordWrap: Boolean) {
        setEditor(initial, wordWrap)
        startProbing()

        // An animated scroll: the content moves a little every frame, like a fling
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 3000f) }
        val firstLines = mutableListOf<Int>()
        val misaligned = advanceAndCollectMisalignments(FRAMES, firstLines)
        // The probe sampled (almost) every frame and the content really travelled
        firstLines.size shouldBeGreaterThan FRAMES - 2
        (firstLines.last() - firstLines.first()) shouldBeGreaterThan 20
        withClue(misaligned.joinToString("\n")) { misaligned.shouldBeEmpty() }
    }

    private fun flingAndCheckEveryFrame(initial: List<String>, wordWrap: Boolean) {
        setEditor(initial, wordWrap)
        startProbing()
        frames.clear()

        // A real touch fling over the text; injecting the swipe advances the paused clock
        composeTestRule.onNodeWithTag(EDITOR_CONTENT_TEST_TAG).performTouchInput {
            swipe(Offset(centerX, bottom - 20f), Offset(centerX, top + 20f), durationMillis = 80)
        }
        val duringSwipe = frames.toList()
        val firstLines = mutableListOf<Int>()
        val misaligned = duringSwipe.mapIndexedNotNull { index, frame ->
            val off = frame.contentTops.keys.filter { it in frame.gutterTops && frame.gutterTops[it] != frame.contentTops[it] }
            if (off.isEmpty()) null else "swipe sample $index: ${off.size} rows off, line ${off.min() + 1} gutter top ${frame.gutterTops[off.min()]} vs content top ${frame.contentTops[off.min()]}"
        } + advanceAndCollectMisalignments(FRAMES, firstLines)
        (firstLines.last() - (duringSwipe.firstOrNull()?.contentTops?.keys?.minOrNull() ?: 0)) shouldBeGreaterThan 10
        withClue(misaligned.joinToString("\n")) { misaligned.shouldBeEmpty() }
    }

    @Test
    fun `gutter follows a touch fling with wrap off`() {
        flingAndCheckEveryFrame((1..400).map { "line-$it" }, wordWrap = false)
    }

    @Test
    fun `gutter follows a touch fling with wrap on`() {
        flingAndCheckEveryFrame(
            (1..400).map { n -> "[$n] " + "lorem ipsum dolor sit amet ".repeat(if (n % 3 == 0) 8 else 1).trim() },
            wordWrap = true,
        )
    }

    @Test
    fun `gutter follows the content in the same frame with wrap off`() {
        scrollAndCheckEveryFrame((1..200).map { "line-$it" }, wordWrap = false)
    }

    @Test
    fun `gutter follows the content in the same frame with wrap on`() {
        scrollAndCheckEveryFrame(
            (1..200).map { n -> "[$n] " + "lorem ipsum dolor sit amet ".repeat(if (n % 3 == 0) 8 else 1).trim() },
            wordWrap = true,
        )
    }

    @Test
    fun `gutter follows a row whose wrapped height changes`() {
        setEditor((1..60).map { "short line $it" }, wordWrap = true)
        startProbing()

        // Line 2 starts wrapping onto several rows: every row below it moves down in that frame
        lines = lines.toMutableList().also { it[1] = "grown " + "lorem ipsum dolor sit amet ".repeat(8).trim() }
        val misaligned = advanceAndCollectMisalignments(5)
        withClue(misaligned.joinToString("\n")) { misaligned.shouldBeEmpty() }
    }

    @Test
    fun `gutter follows the content while the top padding animates`() {
        setEditor((1..200).map { "line-$it" }, wordWrap = false)
        startProbing()

        animatePadding = true
        val misaligned = advanceAndCollectMisalignments(12)
        topPaddingPx shouldBe 70f
        withClue(misaligned.joinToString("\n")) { misaligned.shouldBeEmpty() }
    }

    @Test
    fun `dragging the gutter scrolls the content`() {
        setEditor((1..200).map { "line-$it" }, wordWrap = false)

        // Gutter is the leftmost 40dp; drag well inside it
        composeTestRule.onRoot().performTouchInput {
            swipe(Offset(10f, bottom - 20f), Offset(10f, top + 20f), durationMillis = 300)
        }
        composeTestRule.waitForIdle()

        val frame = sample(composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), lines)
        val first = frame.contentTops.keys.min()
        first shouldBeGreaterThan 5
        val rows = frame.contentTops.keys.filter { it in frame.gutterTops }
        rows.size shouldBeGreaterThan 5
        rows.forEach { frame.gutterTops[it] shouldBe frame.contentTops[it] }
    }

    companion object {
        private const val FRAMES = 20
    }
}
