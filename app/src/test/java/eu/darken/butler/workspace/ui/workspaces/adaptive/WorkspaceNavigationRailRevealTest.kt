package eu.darken.butler.workspace.ui.workspaces.adaptive

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Revealing the focused rail entry: a workspace can gain focus while its entry is scrolled out of
 * view, but the reveal must never fight the user's own gesture or restart on unrelated tab updates.
 */
class WorkspaceNavigationRailRevealTest : BaseTest() {

    private fun info(
        id: Workspace.Id,
        title: String = "Workspace",
        operationCount: Int = 0,
    ) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        operationCount = operationCount,
    )

    @Test
    fun `a focused entry outside the visible window is revealed`() {
        shouldRevealFocused(
            focusedIndex = 7,
            fullyVisibleIndices = listOf(0, 1, 2),
            isDragging = false,
            isScrolling = false,
        ) shouldBe true
    }

    @Test
    fun `a focused entry already on screen is left alone`() {
        shouldRevealFocused(
            focusedIndex = 1,
            fullyVisibleIndices = listOf(0, 1, 2),
            isDragging = false,
            isScrolling = false,
        ) shouldBe false
    }

    /**
     * A clipped entry is still in visibleItemsInfo, but a sliver at the edge of the rail reads as
     * "nothing happened" to the user who just picked that tab.
     */
    @Test
    fun `a focused entry that is only partially visible is revealed`() {
        shouldRevealFocused(
            focusedIndex = 3,
            fullyVisibleIndices = listOf(0, 1, 2),
            isDragging = false,
            isScrolling = false,
        ) shouldBe true
    }

    @Test
    fun `a reorder drag suppresses the reveal`() {
        shouldRevealFocused(
            focusedIndex = 7,
            fullyVisibleIndices = listOf(0, 1, 2),
            isDragging = true,
            isScrolling = false,
        ) shouldBe false
    }

    /** isDragging only covers reorder drags - an ordinary scroll or fling would be yanked back. */
    @Test
    fun `a scroll in progress suppresses the reveal`() {
        shouldRevealFocused(
            focusedIndex = 7,
            fullyVisibleIndices = listOf(0, 1, 2),
            isDragging = false,
            isScrolling = true,
        ) shouldBe false
    }

    @Test
    fun `an unknown focused id is not revealed`() {
        shouldRevealFocused(
            focusedIndex = -1,
            fullyVisibleIndices = listOf(0, 1, 2),
            isDragging = false,
            isScrolling = false,
        ) shouldBe false
    }

    /** Equal keys mean the effect does not restart, so nothing scrolls. */
    @Test
    fun `an info-only update leaves the reveal key unchanged`() {
        val first = Workspace.Id()
        val second = Workspace.Id()
        val before = listOf(info(first, title = "Downloads"), info(second))
        val after = listOf(
            info(first, title = "Downloads (2)", operationCount = 3),
            info(second, title = "Renamed"),
        )

        railRevealKey(first, after) shouldBe railRevealKey(first, before)
    }

    @Test
    fun `a reorder changes the reveal key`() {
        val first = Workspace.Id()
        val second = Workspace.Id()
        val before = listOf(info(first), info(second))

        railRevealKey(first, before.reversed()) shouldNotBe railRevealKey(first, before)
    }

    @Test
    fun `a focus change changes the reveal key`() {
        val first = Workspace.Id()
        val second = Workspace.Id()
        val workspaces = listOf(info(first), info(second))

        railRevealKey(second, workspaces) shouldNotBe railRevealKey(first, workspaces)
    }
}
