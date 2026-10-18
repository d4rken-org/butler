package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class NetworkActionProviderTest : BaseTest() {

    private val first = MockDataProvider.createMockStorageNetwork(name = "Home NAS")
    private val second = MockDataProvider.createMockStorageNetwork(
        name = "Work NAS",
        id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
    )

    private fun actionsFor(vararg selected: ExplorerItem) = NetworkActionProvider().getActions(
        location = ExplorerLocation.Network(items = listOf(first, second)),
        selectionState = ExplorerSelectionState(selectedItems = selected.toSet()),
        viewStyle = ExplorerViewStyle(),
        trashEnabled = false,
    )

    @Test
    fun `without a selection only adding is offered`() {
        val actions = actionsFor()

        actions.any { it is ExplorerActionBarItem.Network.AddLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.EditLocation } shouldBe false
        actions.any { it is ExplorerActionBarItem.Network.RemoveLocation } shouldBe false
    }

    @Test
    fun `one selected location offers edit, info and remove`() {
        val actions = actionsFor(first)

        actions.any { it is ExplorerActionBarItem.Network.EditLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Info } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.RemoveLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.AddLocation } shouldBe false
    }

    @Test
    fun `several selected locations only offer removal`() {
        val actions = actionsFor(first, second)

        actions.any { it is ExplorerActionBarItem.Network.RemoveLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.EditLocation } shouldBe false
        // The sheet describes one share, there is nothing to show for a stack of them.
        actions.any { it is ExplorerActionBarItem.Common.Info } shouldBe false
    }

    private val sessionSmb = MockDataProvider.createMockStorageNetwork(
        name = "Session NAS",
        id = Uuid.parse("33333333-3333-3333-3333-333333333333"),
        rememberCredential = false,
    )
    private val sessionSftp = MockDataProvider.createMockStorageSftp(rememberCredential = false)

    @Test
    fun `a signed-in session-only SMB location offers disconnecting`() {
        actionsFor(sessionSmb).any { it is ExplorerActionBarItem.Network.DisconnectLocation } shouldBe true
    }

    @Test
    fun `a signed-in session-only SFTP location offers disconnecting`() {
        actionsFor(sessionSftp).any { it is ExplorerActionBarItem.Network.DisconnectLocation } shouldBe true
    }

    @Test
    fun `disconnecting is offered right before removal`() {
        val actions = actionsFor(sessionSmb, sessionSftp)

        val disconnect = actions.indexOfFirst { it is ExplorerActionBarItem.Network.DisconnectLocation }
        val remove = actions.indexOfFirst { it is ExplorerActionBarItem.Network.RemoveLocation }
        (disconnect >= 0) shouldBe true
        remove shouldBe disconnect + 1
    }

    @Test
    fun `a location that remembers its password does not offer disconnecting`() {
        actionsFor(first).any { it is ExplorerActionBarItem.Network.DisconnectLocation } shouldBe false
    }

    @Test
    fun `a guest SMB location does not offer disconnecting`() {
        val guest = MockDataProvider.createMockStorageNetwork(
            authType = SmbLocation.AuthType.GUEST,
            rememberCredential = false,
        )

        actionsFor(guest).any { it is ExplorerActionBarItem.Network.DisconnectLocation } shouldBe false
    }

    @Test
    fun `a session-only location that needs signing in does not offer disconnecting`() {
        val signedOut = MockDataProvider.createMockStorageNetwork(
            rememberCredential = false,
            status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
        )

        actionsFor(signedOut).any { it is ExplorerActionBarItem.Network.DisconnectLocation } shouldBe false
    }

    @Test
    fun `one ineligible location in the selection hides disconnecting`() {
        val actions = actionsFor(sessionSmb, first)

        actions.any { it is ExplorerActionBarItem.Network.DisconnectLocation } shouldBe false
        actions.any { it is ExplorerActionBarItem.Network.RemoveLocation } shouldBe true
    }

    /** A new tab would skip the sign-in check, so it is not offered here. */
    @Test
    fun `a selected location does not offer opening in a new tab`() {
        val actions = actionsFor(first)

        actions.any { it is ExplorerActionBarItem.Directory.OpenSelection } shouldBe false
    }

    /** Refreshing is blocked while items are selected, so it must not be offered there either. */
    @Test
    fun `refreshing is only offered without a selection`() {
        actionsFor().any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
        actionsFor(first).any { it is ExplorerActionBarItem.Common.Refresh } shouldBe false
    }

    @Test
    fun `without a selection the list can be sorted, filtered and restyled`() {
        val actions = actionsFor()

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.ViewOptions } shouldBe true
    }

    @Test
    fun `a selection hides sort, filter and view-style`() {
        val actions = actionsFor(first)

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.ViewOptions } shouldBe false
    }
}
