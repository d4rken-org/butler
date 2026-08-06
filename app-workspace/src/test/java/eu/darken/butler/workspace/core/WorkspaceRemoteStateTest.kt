package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The counts derived from a workspace snapshot. [WorkspaceRemote.State.workspaceCount] is what the
 * Butler button badges, so it has to mean the same thing as the tab limit the user runs into.
 */
class WorkspaceRemoteStateTest : BaseTest() {

    private fun info(
        type: Workspace.Type = Workspace.Type.EXPLORER,
        caller: Workspace.Id? = null,
    ) = Workspace.Info(
        id = Workspace.Id(),
        type = type,
        title = "Workspace".toCaString(),
        callerWorkspaceId = caller,
    )

    @Test
    fun `workspaceCount counts open tabs`() {
        val state = WorkspaceRemote.State(infos = listOf(info(), info(), info()))

        state.workspaceCount shouldBe 3
    }

    @Test
    fun `a stacked sub-workspace does not raise the count`() {
        val tab = info()

        val state = WorkspaceRemote.State(
            infos = listOf(
                tab,
                // An App Details peek or a picker: it occupies no tab of its own and does not count
                // toward the limit either, so badging it would contradict what the user hits.
                info(type = Workspace.Type.APP_DETAILS, caller = tab.id),
            ),
        )

        state.workspaceCount shouldBe 1
    }

    @Test
    fun `quota-exempt tabs are still counted`() {
        val state = WorkspaceRemote.State(
            infos = listOf(
                info(),
                // Exempt from the limit, but real tabs the user can switch to - the badge counts
                // what is open, not what is billable.
                info(type = Workspace.Type.DEVELOPER),
                info(type = Workspace.Type.BUG_REPORT),
            ),
        )

        Workspace.Type.DEVELOPER.isQuotaExempt shouldBe true
        Workspace.Type.BUG_REPORT.isQuotaExempt shouldBe true
        state.workspaceCount shouldBe 3
    }
}
