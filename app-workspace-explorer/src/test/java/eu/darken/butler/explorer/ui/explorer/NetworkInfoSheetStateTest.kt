package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.network.NetworkEndpointState
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class NetworkInfoSheetStateTest : BaseTest() {

    private val checking = MockDataProvider.createMockStorageNetwork()
    private val reachable = checking.copy(
        endpoint = NetworkEndpointState("192.168.1.50", NetworkEndpointState.Reachability.REACHABLE),
    )

    private fun sheetFor() = ExplorerDialogState.ItemInfo(
        ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(checking.location.id),
    )

    @Test
    fun `a sheet opened while the address was unknown shows it once the probe answers`() {
        val opened = sheetFor().withLiveNetworkItem(listOf(checking))
        opened.shouldBeInstanceOf<ExplorerDialogState.ItemInfo>()
        (opened.context as ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork).item shouldBe checking

        val probed = sheetFor().withLiveNetworkItem(listOf(reachable))
        probed.shouldBeInstanceOf<ExplorerDialogState.ItemInfo>()
        (probed.context as ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork).item shouldBe reachable
    }

    @Test
    fun `a location that left the listing resolves to nothing`() {
        val resolved = sheetFor().withLiveNetworkItem(emptyList())

        resolved.shouldBeInstanceOf<ExplorerDialogState.ItemInfo>()
        (resolved.context as ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork).item shouldBe null
    }

    @Test
    fun `an SFTP sheet resolves to its own row, not to an SMB row next to it`() {
        val sftp = MockDataProvider.createMockStorageSftp(
            endpoint = NetworkEndpointState("192.168.1.20", NetworkEndpointState.Reachability.REACHABLE),
        )
        val sheet = ExplorerDialogState.ItemInfo(
            ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(sftp.location.id),
        )

        val resolved = sheet.withLiveNetworkItem(listOf(checking, sftp))

        resolved.shouldBeInstanceOf<ExplorerDialogState.ItemInfo>()
        (resolved.context as ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork).item shouldBe sftp
    }

    @Test
    fun `every other dialog is left alone`() {
        val other = ExplorerDialogState.ItemInfo(
            ExplorerDialogState.ItemInfo.InfoContext.SingleLocalStorage(MockDataProvider.createMockStorageLocal()),
        )

        other.withLiveNetworkItem(listOf(checking)) shouldBe other
        ExplorerDialogState.None.withLiveNetworkItem(listOf(checking)) shouldBe ExplorerDialogState.None
    }
}
