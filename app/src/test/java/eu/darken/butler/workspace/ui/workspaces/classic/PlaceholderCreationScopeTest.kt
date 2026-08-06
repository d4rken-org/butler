package eu.darken.butler.workspace.ui.workspaces.classic

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceStacks
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Which tabs may still be swiped off onto the trailing creation placeholder.
 *
 * Once a pane-local child stacks inside its own tab's page instead of covering the screen, that page
 * is one swipe away from the creation placeholder - and creating a tab out from under a picker or
 * the Saver strands the result its caller is waiting for.
 */
class PlaceholderCreationScopeTest : BaseTest() {

    private fun info(
        id: Workspace.Id = Workspace.Id(),
        caller: Workspace.Id? = null,
        pausableAsChild: Boolean = false,
        isPausable: Boolean = true,
    ) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Workspace ${id.shortTag}".toCaString(),
        callerWorkspaceId = caller,
        pausableAsChild = pausableAsChild,
        isPausable = isPausable,
    )

    @Test
    fun `a plain tab may create`() {
        val tab = Workspace.Id()

        WorkspaceStacks(listOf(info(tab))).creationAllowedFor(tab) shouldBe true
    }

    @Test
    fun `a tab owning a result-returning child may not create`() {
        val tab = Workspace.Id()
        val saver = Workspace.Id()

        // The Saver and every picker: their result collector lives in the caller
        WorkspaceStacks(listOf(info(tab), info(saver, caller = tab)))
            .creationAllowedFor(tab) shouldBe false
    }

    @Test
    fun `a tab owning only opted-in children may create`() {
        val tab = Workspace.Id()
        val details = Workspace.Id()

        // App Details owes nobody a result, so leaving it behind loses nothing
        WorkspaceStacks(listOf(info(tab), info(details, caller = tab, pausableAsChild = true)))
            .creationAllowedFor(tab) shouldBe true
    }

    @Test
    fun `a nested result-returning child blocks its whole unit`() {
        val tab = Workspace.Id()
        val details = Workspace.Id()
        val picker = Workspace.Id()

        WorkspaceStacks(
            listOf(
                info(tab),
                info(details, caller = tab, pausableAsChild = true),
                info(picker, caller = details),
            ),
        ).creationAllowedFor(tab) shouldBe false
    }

    @Test
    fun `a composed-out sibling branch still blocks`() {
        val tab = Workspace.Id()
        val visibleBranch = Workspace.Id()
        val hiddenPicker = Workspace.Id()

        // Only one branch per tab is ever rendered, but the other one is just as open - which is why
        // the whole ownership unit is consulted rather than the preferred chain.
        WorkspaceStacks(
            listOf(
                info(tab),
                info(visibleBranch, caller = tab, pausableAsChild = true),
                info(hiddenPicker, caller = tab),
            ),
        ).creationAllowedFor(tab) shouldBe false
    }

    @Test
    fun `a child that is transiently unpausable does not block`() {
        val tab = Workspace.Id()
        val details = Workspace.Id()

        // App Details flips isPausable while a package operation runs. Keying on that instead of on
        // pausableAsChild would disable tab creation for the duration, for no reason at all.
        WorkspaceStacks(
            listOf(
                info(tab),
                info(details, caller = tab, pausableAsChild = true, isPausable = false),
            ),
        ).creationAllowedFor(tab) shouldBe true
    }

    @Test
    fun `an unresolvable root does not block`() {
        val tab = Workspace.Id()

        // No root to reason about, so the ordinary behaviour stands rather than a permanent block
        WorkspaceStacks(listOf(info(tab))).creationAllowedFor(null) shouldBe true
        WorkspaceStacks(listOf(info(tab))).creationAllowedFor(Workspace.Id()) shouldBe true
    }
}
