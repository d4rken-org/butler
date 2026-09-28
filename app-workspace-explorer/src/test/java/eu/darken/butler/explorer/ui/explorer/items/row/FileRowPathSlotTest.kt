package eu.darken.butler.explorer.ui.explorer.items.row

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest
import kotlin.math.abs
import kotlin.time.Instant

/**
 * A path on a row's second line is its own left-to-right node, so an RTL layout keeps its root
 * slash in front; the metadata after it stays a separate node, and neither the metadata nor a long
 * timestamp at the end of the line may squeeze the path out of a narrow row.
 *
 * Real font metrics, because the synthetic ones give about a pixel per character and nothing on a
 * 220dp row would ever compete for room.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileRowPathSlotTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val size get() = formatFileSize(context, SIZE_BYTES, shortFormat = false)
    private val brokenLabel get() = context.getString(R.string.explorer_file_broken_link_label)

    private fun recentFile(withParent: Boolean, withSize: Boolean): ExplorerItem.RegularFile {
        val path = LocalPath.build("$PARENT/$NAME")
        val lookup = object : APathLookup<LocalPath> {
            override val lookedUp: LocalPath = path
            override val parent: LocalPath? = if (withParent) path.parent else null
            override val fileType: FileType = FileType.FILE
            override val size: Long? = if (withSize) SIZE_BYTES else null
            override val modifiedAt: Instant? = null
            override val target: APath<*>? = null
            override val ownership: Ownership? = null
            override val permissions: Permissions? = null
            override val createdAt: Instant? = null
            override val error: String? = null
        }
        return ExplorerItem.RegularFile(lookup = lookup, mimeType = MimeInfo.fromFileName(NAME))
    }

    private fun render(direction: LayoutDirection, row: @Composable (Modifier) -> Unit) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    row(Modifier.width(220.dp).testTag(ROW_TAG))
                }
            }
        }
    }

    private fun renderRecent(
        direction: LayoutDirection,
        withParent: Boolean = true,
        withSize: Boolean = true,
    ) = render(direction) { modifier ->
        RecentItemRow(
            modifier = modifier,
            item = recentFile(withParent = withParent, withSize = withSize),
            indexedAt = LONG_AGO,
            density = ExplorerViewStyle.Density.COMFORTABLE,
        )
    }

    private fun renderSymlink(direction: LayoutDirection, targetPath: String?, isBroken: Boolean) =
        render(direction) { modifier ->
            SymlinkFileRow(
                modifier = modifier,
                item = MockDataProvider.createMockSymbolicLink(SYMLINK_NAME, targetPath, isBroken),
                density = ExplorerViewStyle.Density.COMFORTABLE,
                isSelected = false,
                onToggleSelection = {},
                onClick = {},
                showSelection = false,
            )
        }

    private fun node(text: String) = composeTestRule.onNodeWithText(text, useUnmergedTree = true)

    private fun exists(text: String) = composeTestRule
        .onAllNodes(hasText(text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        return results.single()
    }

    private fun bounds(text: String): Rect = node(text).fetchSemanticsNode().boundsInRoot

    private fun rowTexts(): List<String> = composeTestRule.onNodeWithTag(ROW_TAG)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .map { it.text }

    private fun assertPathNode(path: String, direction: LayoutDirection, startEdgeOf: String) {
        layoutOf(path).layoutInput.style.textDirection shouldBe TextDirection.Ltr
        node(path).fetchSemanticsNode().size.width shouldBeGreaterThan 0

        val pathBounds = bounds(path)
        val nameBounds = bounds(startEdgeOf)
        val startEdgeGap = when (direction) {
            LayoutDirection.Ltr -> pathBounds.left - nameBounds.left
            LayoutDirection.Rtl -> pathBounds.right - nameBounds.right
        }
        abs(startEdgeGap) shouldBeLessThan 1f
    }

    private fun recentPathThenSize(direction: LayoutDirection) {
        renderRecent(direction)

        assertPathNode(PARENT, direction, startEdgeOf = NAME)
        exists(size) shouldBe true
        rowTexts().shouldContainInOrder(PARENT, size)
    }

    @Test
    fun `recent row keeps the folder and the size apart in an rtl layout`() = recentPathThenSize(LayoutDirection.Rtl)

    @Test
    fun `recent row keeps the folder and the size apart in an ltr layout`() = recentPathThenSize(LayoutDirection.Ltr)

    @Test
    fun `recent row without a size shows the folder alone`() {
        renderRecent(LayoutDirection.Rtl, withSize = false)

        assertPathNode(PARENT, LayoutDirection.Rtl, startEdgeOf = NAME)
        rowTexts() shouldNotContain SEPARATOR
    }

    @Test
    fun `recent row without a folder shows the size alone`() {
        renderRecent(LayoutDirection.Rtl, withParent = false)

        exists(size) shouldBe true
        rowTexts() shouldNotContain SEPARATOR
    }

    private fun symlinkTargetThenBrokenLabel(direction: LayoutDirection) {
        renderSymlink(direction, targetPath = TARGET, isBroken = true)

        assertPathNode("→ $TARGET", direction, startEdgeOf = SYMLINK_NAME)
        exists(brokenLabel) shouldBe true
        rowTexts().shouldContainInOrder("→ $TARGET", brokenLabel)
    }

    @Test
    fun `broken symlink keeps its target and the label apart in an rtl layout`() =
        symlinkTargetThenBrokenLabel(LayoutDirection.Rtl)

    @Test
    fun `broken symlink keeps its target and the label apart in an ltr layout`() =
        symlinkTargetThenBrokenLabel(LayoutDirection.Ltr)

    @Test
    fun `intact symlink shows its target alone`() {
        renderSymlink(LayoutDirection.Rtl, targetPath = TARGET, isBroken = false)

        assertPathNode("→ $TARGET", LayoutDirection.Rtl, startEdgeOf = SYMLINK_NAME)
        rowTexts() shouldNotContain SEPARATOR
    }

    @Test
    fun `broken symlink without a known target shows the label alone`() {
        renderSymlink(LayoutDirection.Rtl, targetPath = null, isBroken = true)

        exists(brokenLabel) shouldBe true
        rowTexts() shouldNotContain SEPARATOR
    }

    companion object {
        private const val ROW_TAG = "row.under.test"
        private const val PARENT = "/storage/emulated/0/Download/work/reports"
        private const val NAME = "quarterly_report_final.pdf"
        private const val SIZE_BYTES = 843_776L
        private const val SYMLINK_NAME = "docs_link"
        private const val TARGET = "/storage/emulated/0/Documents/Projects/Archive/2026"
        private const val SEPARATOR = " • "

        /** Old enough for an absolute date, the widest form the end of the line takes. */
        private val LONG_AGO = Instant.parse("2025-12-31T23:59:59Z")
    }
}
