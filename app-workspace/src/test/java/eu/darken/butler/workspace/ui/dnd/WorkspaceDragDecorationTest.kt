package eu.darken.butler.workspace.ui.dnd

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspaceDragDecorationTest : BaseTest() {

    private val density = Density(density = 2f, fontScale = 1f)
    private val sourceSize = Size(400f, 120f)

    private fun dir(path: String) = WorkspaceDragPayload.Item(
        path = LocalPath.build(path),
        kind = WorkspaceDragPayload.Kind.DIRECTORY,
    )

    private fun file(path: String) = WorkspaceDragPayload.Item(
        path = LocalPath.build(path),
        kind = WorkspaceDragPayload.Kind.FILE_OTHER,
    )

    private fun textFile(path: String) = WorkspaceDragPayload.Item(
        path = LocalPath.build(path),
        kind = WorkspaceDragPayload.Kind.FILE_TEXT,
    )

    private fun items(count: Int) = (1..count).map { file("/storage/emulated/0/file-$it") }

    private fun layout(
        spec: DragDecorationSpec,
        density: Density = this.density,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        countSize: IntSize = IntSize(120, 40),
        breakdownSize: IntSize? = IntSize(90, 32),
    ) = decorationLayout(
        spec = spec,
        sourceSize = sourceSize,
        density = density,
        layoutDirection = layoutDirection,
        countSize = countSize,
        breakdownSize = breakdownSize,
    )

    @Test
    fun `an empty selection has nothing to draw`() {
        dragDecorationSpec(emptyList()) shouldBe null
    }

    @Test
    fun `a single item drags as itself`() {
        dragDecorationSpec(items(1)) shouldBe DragDecorationSpec.Single
    }

    @Test
    fun `two or three items drag as a stack`() {
        dragDecorationSpec(items(2)) shouldBe DragDecorationSpec.Stack(2)
        dragDecorationSpec(items(3)) shouldBe DragDecorationSpec.Stack(3)
    }

    @Test
    fun `four or more items drag as a summary`() {
        dragDecorationSpec(items(4)) shouldBe DragDecorationSpec.Summary(total = 4, folders = 0, files = 4)
        dragDecorationSpec(items(5)) shouldBe DragDecorationSpec.Summary(total = 5, folders = 0, files = 5)
        dragDecorationSpec(items(20)) shouldBe DragDecorationSpec.Summary(total = 20, folders = 0, files = 20)
    }

    @Test
    fun `the summary counts folders apart from files`() {
        dragDecorationSpec(
            listOf(dir("/a"), dir("/b"), dir("/c"), dir("/d")),
        ) shouldBe DragDecorationSpec.Summary(total = 4, folders = 4, files = 0)

        dragDecorationSpec(
            listOf(dir("/a"), dir("/b"), file("/c.jpg"), textFile("/d.txt")),
        ) shouldBe DragDecorationSpec.Summary(total = 4, folders = 2, files = 2)
    }

    @Test
    fun `both file kinds count as files`() {
        dragDecorationSpec(
            listOf(textFile("/a.txt"), textFile("/b.txt"), file("/c.jpg"), file("/d.jpg")),
        ) shouldBe DragDecorationSpec.Summary(total = 4, folders = 0, files = 4)
    }

    @Test
    fun `a single item decoration is the size of the dragged item`() {
        val layout = layout(DragDecorationSpec.Single)
        layout.size shouldBe sourceSize
        layout.plates.size shouldBe 1
    }

    @Test
    fun `the stack grows by one offset per extra card`() {
        val step = with(density) { STACK_OFFSET.toPx() }

        val two = layout(DragDecorationSpec.Stack(2))
        two.plates.size shouldBe 2
        two.size shouldBe Size(sourceSize.width + step, sourceSize.height + step)

        val three = layout(DragDecorationSpec.Stack(3))
        three.plates.size shouldBe 3
        three.size shouldBe Size(sourceSize.width + step * 2, sourceSize.height + step * 2)
    }

    @Test
    fun `the summary width is capped`() {
        val capped = layout(
            spec = DragDecorationSpec.Summary(total = 12, folders = 4, files = 8),
            countSize = IntSize(4000, 40),
        )
        capped.size.width shouldBe with(density) { SUMMARY_MAX_WIDTH.toPx() }
    }

    @Test
    fun `the summary grows with the font scale`() {
        val spec = DragDecorationSpec.Summary(total = 12, folders = 4, files = 8)
        val normal = Density(density = 2f, fontScale = 1f)
        val scaled = Density(density = 2f, fontScale = 2f)

        fun heightAt(density: Density): Float {
            val line = IntSize(
                width = with(density) { 100.dp.roundToPx() },
                height = with(density) { 20.sp.roundToPx() },
            )
            return layout(spec, density = density, countSize = line, breakdownSize = line).size.height
        }

        heightAt(scaled) shouldBeGreaterThan heightAt(normal)
    }

    @Test
    fun `right to left mirrors the summary icon to the end edge`() {
        val spec = DragDecorationSpec.Summary(total = 12, folders = 4, files = 8)
        val padding = with(density) { SUMMARY_PADDING.toPx() }
        val icon = with(density) { SUMMARY_ICON.toPx() }
        val gap = with(density) { SUMMARY_GAP.toPx() }
        val countSize = IntSize(120, 40)

        val ltr = layout(spec, countSize = countSize)
        ltr.iconBounds!!.left shouldBe padding
        ltr.countOffset!!.x shouldBe padding + icon + gap

        val rtl = layout(spec, layoutDirection = LayoutDirection.Rtl, countSize = countSize)
        rtl.iconBounds!!.left shouldBe rtl.size.width - padding - icon
        rtl.countOffset!!.x shouldBe rtl.size.width - padding - icon - gap - countSize.width
    }

    @Test
    fun `right to left fans the stack toward the start edge`() {
        val step = with(density) { STACK_OFFSET.toPx() }
        val spec = DragDecorationSpec.Stack(3)

        val ltr = layout(spec)
        ltr.plates.first().left shouldBe step * 2
        ltr.plates.last().left shouldBe 0f

        val rtl = layout(spec, layoutDirection = LayoutDirection.Rtl)
        rtl.plates.first().left shouldBe 0f
        rtl.plates.last().left shouldBe step * 2
    }
}
