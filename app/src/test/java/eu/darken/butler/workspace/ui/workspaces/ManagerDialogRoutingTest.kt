package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The tab manager overlay covers every pane, so while it is up it - not the pane - has to host a
 * close confirmation, and a full-screen modal window covers the overlay in turn. Exactly one host
 * may compose a given dialog.
 */
class ManagerDialogRoutingTest : BaseTest() {

    private val tabA = Workspace.Id()
    private val tabB = Workspace.Id()
    private val tabOrder = listOf(tabA, tabB)

    private fun closeConfirmation(
        id: String,
        closing: Workspace.Id,
        host: Workspace.Id = closing,
    ) = ManagerDialog.WorkspaceTargeted.CloseConfirmation(
        id = id,
        targetWorkspaceId = host,
        closingWorkspaceId = closing,
        workspaceTitle = "notes.txt".toCaString(),
        hasUnsavedChanges = true,
    )

    private fun globalConfirmation(id: String, closing: Workspace.Id) =
        ManagerDialog.Global.CloseConfirmation(
            id = id,
            closingWorkspaceId = closing,
            workspaceTitle = "notes.txt".toCaString(),
            hasUnsavedChanges = true,
            selectionSourceWorkspaceId = null,
        )

    private fun batchConfirmation(host: Workspace.Id) =
        ManagerDialog.WorkspaceTargeted.BatchCreationConfirmation(
            id = "batch",
            targetWorkspaceId = host,
            totalCount = 7,
        )

    @Test
    fun `without the overlay a close confirmation stays with its pane`() {
        val dialog = closeConfirmation("c1", closing = tabA)

        val routing = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = false,
            tabOrder = tabOrder,
        )

        routing.paneHosted shouldBe mapOf(tabA to dialog)
        routing.managerHosted.shouldBeNull()
        routing.modalHosted.shouldBeNull()
        routing.globalHosted.shouldBeNull()
    }

    @Test
    fun `the overlay takes the close confirmation off the pane`() {
        val dialog = closeConfirmation("c1", closing = tabA, host = tabB)

        val routing = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )

        // Withheld from the pane AND offered to the manager: either half alone is a bug - one
        // renders it twice, the other renders it nowhere.
        routing.paneHosted shouldBe emptyMap()
        routing.managerHosted shouldBe dialog
        routing.modalHosted.shouldBeNull()
        routing.globalHosted.shouldBeNull()
    }

    @Test
    fun `ownership returns to the pane when the overlay goes away`() {
        val dialog = closeConfirmation("c1", closing = tabA)

        val whileVisible = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )
        val afterHiding = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = false,
            tabOrder = tabOrder,
        )

        whileVisible.paneHosted shouldBe emptyMap()
        afterHiding.managerHosted.shouldBeNull()
        afterHiding.paneHosted shouldBe mapOf(tabA to dialog)
    }

    @Test
    fun `the manager asks about one tab at a time`() {
        val first = closeConfirmation("c2", closing = tabA)
        val second = closeConfirmation("c1", closing = tabB)

        val routing = routeManagerDialogs(
            dialogs = listOf(second, first),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )

        // Oldest tab first, regardless of the order the confirmations were queued in.
        routing.managerHosted shouldBe first
        routing.paneHosted shouldBe emptyMap()
    }

    @Test
    fun `the next confirmation follows once the current one resolves`() {
        val first = closeConfirmation("c1", closing = tabA)
        val second = closeConfirmation("c2", closing = tabB)

        val pending = routeManagerDialogs(
            dialogs = listOf(first, second),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )
        val afterResolve = routeManagerDialogs(
            dialogs = listOf(second),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )

        pending.managerHosted shouldBe first
        afterResolve.managerHosted shouldBe second
    }

    @Test
    fun `a batch confirmation keeps its pane host under the overlay`() {
        val batch = batchConfirmation(host = tabA)
        val close = closeConfirmation("c1", closing = tabB)

        val routing = routeManagerDialogs(
            dialogs = listOf(batch, close),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )

        routing.paneHosted shouldBe mapOf(tabA to batch)
        routing.managerHosted shouldBe close
    }

    @Test
    fun `a confirmation for an unlisted tab still reaches the manager`() {
        val closed = Workspace.Id()
        val dialog = closeConfirmation("c1", closing = closed)

        val routing = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )

        routing.managerHosted shouldBe dialog
    }

    @Test
    fun `the limit dialog is not routed to any workspace host`() {
        val limit = ManagerDialog.Global.WorkspaceLimitReached(
            id = "limit",
            currentCount = 5,
            limit = 5,
        )

        val routing = routeManagerDialogs(
            dialogs = listOf(limit),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )

        routing.paneHosted shouldBe emptyMap()
        routing.managerHosted.shouldBeNull()
        routing.modalHosted.shouldBeNull()
        routing.globalHosted.shouldBeNull()
    }

    @Test
    fun `the modal window hosts the confirmation anchored to it, not the manager`() {
        val modal = Workspace.Id()
        val dialog = closeConfirmation("c1", closing = tabA, host = modal)

        val routing = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
            fullScreenModalId = modal,
        )

        // The modal is a window drawn above the overlay, so a dialog the manager composed would end
        // up behind the workspace it is anchored to.
        routing.modalHosted shouldBe dialog
        routing.managerHosted.shouldBeNull()
        routing.paneHosted shouldBe emptyMap()
    }

    @Test
    fun `the modal window hosts the confirmation anchored to it, not a pane`() {
        val modal = Workspace.Id()
        val dialog = closeConfirmation("c1", closing = tabA, host = modal)

        val routing = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = false,
            tabOrder = tabOrder,
            fullScreenModalId = modal,
        )

        routing.modalHosted shouldBe dialog
        routing.paneHosted shouldBe emptyMap()
    }

    @Test
    fun `a confirmation anchored elsewhere stays off the modal`() {
        val modal = Workspace.Id()
        val dialog = closeConfirmation("c1", closing = tabA)

        val routing = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = false,
            tabOrder = tabOrder,
            fullScreenModalId = modal,
        )

        routing.modalHosted.shouldBeNull()
        routing.paneHosted shouldBe mapOf(tabA to dialog)
    }

    @Test
    fun `a global close confirmation reaches only the screen`() {
        val dialog = globalConfirmation("c1", closing = tabA)

        val routing = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = false,
            tabOrder = tabOrder,
        )

        routing.globalHosted shouldBe dialog
        routing.paneHosted shouldBe emptyMap()
        routing.managerHosted.shouldBeNull()
        routing.modalHosted.shouldBeNull()
    }

    @Test
    fun `the screen asks about one tab at a time`() {
        val first = globalConfirmation("c2", closing = tabA)
        val second = globalConfirmation("c1", closing = tabB)

        val routing = routeManagerDialogs(
            dialogs = listOf(second, first),
            isManagerOverlayVisible = false,
            tabOrder = tabOrder,
        )

        // A window dialog covers the screen, so a second one would render behind the first.
        routing.globalHosted shouldBe first
    }

    @Test
    fun `the overlay does not change who hosts a global close confirmation`() {
        val dialog = globalConfirmation("c1", closing = tabA)

        val whileVisible = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = true,
            tabOrder = tabOrder,
        )
        val afterHiding = routeManagerDialogs(
            dialogs = listOf(dialog),
            isManagerOverlayVisible = false,
            tabOrder = tabOrder,
        )

        // A window dialog draws above the overlay, so the manager has nothing to take over.
        whileVisible.globalHosted shouldBe dialog
        whileVisible.managerHosted.shouldBeNull()
        afterHiding.globalHosted shouldBe dialog
    }
}
