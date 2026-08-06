package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.toCaString
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The ownership walk everything that renders, pauses or focuses a workspace agrees on.
 *
 * Presentation is decided here and nowhere else: only an explicit [Workspace.ModalPresentationMode
 * .FULL_SCREEN] member makes a chain cover the screen, and the pane count is deliberately not an
 * input - a pane-local chain stacks inside its owning tab on a phone exactly as it does in a tablet
 * pane.
 */
class WorkspaceStacksTest : BaseTest() {

    private fun info(
        id: Workspace.Id = Workspace.Id(),
        caller: Workspace.Id? = null,
        presentation: Workspace.ModalPresentationMode = Workspace.ModalPresentationMode.PANE_LOCAL,
    ) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Workspace ${id.shortTag}".toCaString(),
        callerWorkspaceId = caller,
        modalPresentation = presentation,
    )

    @Test
    fun `rootOf resolves a whole chain to its tab`() {
        val tab = Workspace.Id()
        val child = Workspace.Id()
        val grandChild = Workspace.Id()
        val stacks = WorkspaceStacks(
            listOf(info(tab), info(child, caller = tab), info(grandChild, caller = child)),
        )

        stacks.rootOf(tab)?.id shouldBe tab
        stacks.rootOf(child)?.id shouldBe tab
        stacks.rootOf(grandChild)?.id shouldBe tab
    }

    @Test
    fun `unitOf lists the root first, then everything it owns`() {
        val tab = Workspace.Id()
        val child = Workspace.Id()
        val sibling = Workspace.Id()
        val stacks = WorkspaceStacks(
            listOf(info(tab), info(child, caller = tab), info(sibling, caller = tab)),
        )

        stacks.unitOf(child)!!.map { it.id } shouldContainExactly listOf(tab, child, sibling)
    }

    @Test
    fun `an explicit FULL_SCREEN member promotes the whole chain`() {
        val tab = Workspace.Id()
        val picker = Workspace.Id()

        val rendered = WorkspaceStacks(
            listOf(
                info(tab),
                info(picker, caller = tab, presentation = Workspace.ModalPresentationMode.FULL_SCREEN),
            ),
        ).renderedChains(focusedId = tab)

        rendered.fullScreen?.leaf?.id shouldBe picker
        rendered.paneLocal shouldBe emptyMap()
    }

    @Test
    fun `a pane-local descendant of a full-screen parent stays full-screen`() {
        val tab = Workspace.Id()
        val picker = Workspace.Id()
        val details = Workspace.Id()

        val rendered = WorkspaceStacks(
            listOf(
                info(tab),
                info(picker, caller = tab, presentation = Workspace.ModalPresentationMode.FULL_SCREEN),
                info(details, caller = picker),
            ),
        ).renderedChains(focusedId = tab)

        rendered.fullScreen?.leaf?.id shouldBe details
        rendered.paneLocal shouldBe emptyMap()
    }

    @Test
    fun `a pane-local leaf is never promoted, whatever the chain looks like`() {
        val tab = Workspace.Id()
        val details = Workspace.Id()
        val saver = Workspace.Id()

        val rendered = WorkspaceStacks(
            listOf(info(tab), info(details, caller = tab), info(saver, caller = details)),
        ).renderedChains(focusedId = tab)

        rendered.fullScreen shouldBe null
        rendered.paneLocal.keys shouldBe setOf(tab)
        rendered.paneLocal[tab]!!.modals.map { it.id } shouldContainExactly listOf(details, saver)
    }

    @Test
    fun `the rendered chain is the one focus points into`() {
        val tab = Workspace.Id()
        val shared = Workspace.Id()
        val older = Workspace.Id()
        val newer = Workspace.Id()
        val infos = listOf(
            info(tab),
            info(shared, caller = tab),
            info(older, caller = shared),
            info(newer, caller = shared),
        )

        // Focus on a leaf identifies its branch, even though a newer sibling exists
        WorkspaceStacks(infos).renderedChains(focusedId = older)
            .paneLocal[tab]!!.modals.map { it.id } shouldContainExactly listOf(shared, older)

        // Focus on the workspace's own root identifies no branch, so the newest one wins
        WorkspaceStacks(infos).renderedChains(focusedId = tab)
            .paneLocal[tab]!!.modals.map { it.id } shouldContainExactly listOf(shared, newer)
    }

    @Test
    fun `sibling branches of one root tie-break by newest`() {
        val tab = Workspace.Id()
        val first = Workspace.Id()
        val second = Workspace.Id()

        val rendered = WorkspaceStacks(
            listOf(info(tab), info(first, caller = tab), info(second, caller = tab)),
        ).renderedChains(focusedId = null)

        // One entry per owning tab: two branches cannot both be on top of the same page
        rendered.paneLocal.keys shouldBe setOf(tab)
        rendered.paneLocal[tab]!!.modals.map { it.id } shouldContainExactly listOf(second)
    }

    @Test
    fun `a chain whose caller no longer exists is dropped`() {
        val tab = Workspace.Id()
        val orphan = Workspace.Id()

        val stacks = WorkspaceStacks(listOf(info(tab), info(orphan, caller = Workspace.Id())))

        stacks.rootOf(orphan) shouldBe null
        stacks.unitOf(orphan) shouldBe null
        stacks.renderedChains(focusedId = orphan).let {
            it.fullScreen shouldBe null
            it.paneLocal shouldBe emptyMap()
        }
    }

    @Test
    fun `a chain running into a cycle is dropped instead of looping`() {
        val leaf = Workspace.Id()
        val a = Workspace.Id()
        val b = Workspace.Id()

        // leaf -> a -> b -> a: a real leaf hanging off a cycle, so the leaf filter alone does not
        // catch it and the walk has to terminate on its own.
        val stacks = WorkspaceStacks(
            listOf(info(leaf, caller = a), info(a, caller = b), info(b, caller = a)),
        )

        stacks.rootOf(leaf) shouldBe null
        stacks.renderedChains(focusedId = leaf).let {
            it.fullScreen shouldBe null
            it.paneLocal shouldBe emptyMap()
        }
    }
}
