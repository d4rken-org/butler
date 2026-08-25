package eu.darken.butler.explorer.ui.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Lan
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerItemListChangeTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun networkItem(
        label: String = "Home NAS",
        status: ExplorerItem.Storage.Network.Status = ExplorerItem.Storage.Network.Status.AVAILABLE,
    ): ExplorerItem.Storage.Network {
        val location = SmbLocation(
            id = locationId,
            label = label,
            host = "nas.local",
            share = "media",
            authType = SmbLocation.AuthType.PASSWORD,
            rememberCredential = true,
            credentialVersion = 1,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
        return ExplorerItem.Storage.Network(
            location = location,
            displayName = label.toCaString(),
            displayIcon = Icons.TwoTone.Lan,
            target = ExplorerNavigation.Target.Directory(location.rootPath),
            subtitle = location.endpointLabel.toCaString(),
            status = status,
        )
    }

    @Test
    fun `a re-listing that produced the same lookups again is dropped`() {
        val old = listOf(MockDataProvider.createMockRegularFile("readme.txt"))
        val new = listOf(
            MockDataProvider.createMockRegularFile(
                name = "readme.txt",
                modifiedAt = Instant.parse("2024-01-01T00:00:00Z"),
            ),
        )

        old.hasSameItemsAs(new) shouldBe true
    }

    @Test
    fun `a peek that became a lookup gets through`() {
        val old = listOf(MockDataProvider.createMockPeek("readme.txt"))
        val new = listOf(MockDataProvider.createMockRegularFile("readme.txt"))

        old.hasSameItemsAs(new) shouldBe false
    }

    @Test
    fun `an unchanged network row is dropped`() {
        val item = networkItem()

        listOf(item).hasSameItemsAs(listOf(item)) shouldBe true
    }

    /** The id stays `network-<uuid>` across a rename, so only full equality can see the new label. */
    @Test
    fun `a renamed network location gets through`() {
        val old = listOf(networkItem(label = "Home NAS"))
        val new = listOf(networkItem(label = "Basement NAS"))

        old.hasSameItemsAs(new) shouldBe false
    }

    @Test
    fun `a changed network status gets through`() {
        val old = listOf(networkItem())
        val new = listOf(networkItem(status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED))

        old.hasSameItemsAs(new) shouldBe false
    }
}
