package eu.darken.butler.explorer.core.engine

import android.content.Context
import android.content.res.Resources
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.network.NetworkLocation
import eu.darken.butler.common.files.network.NetworkLocationRepo
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class HomeLocationLoaderTest : BaseTest() {

    private fun loader(networkLocations: List<NetworkLocation>) = HomeLocationLoader(
        workspaceId = Workspace.Id(),
        trashRepo = mockk<TrashRepo>().apply {
            every { getAllItems() } returns flowOf(emptyList())
        },
        trashSettings = mockk<TrashSettings>().apply {
            every { enabled } returns mockk<DataStoreValue<Boolean>>().apply {
                every { flow } returns flowOf(true)
            }
        },
        networkLocationRepo = mockk<NetworkLocationRepo>().apply {
            every { locations } returns flowOf(networkLocations)
        },
    )

    /** Renders the plural as its count, so the assertion reads the number the shortcut was built with. */
    private val countingContext = mockk<Context>().apply {
        every { resources } returns mockk<Resources>().apply {
            every { getQuantityString(any(), any(), *anyVararg()) } answers { secondArg<Int>().toString() }
        }
    }

    private suspend fun HomeLocationLoader.networkSubtitle(): String? {
        val home = loadHome().last().shouldBeInstanceOf<ExplorerLocation.Home>()
        val shortcut = home.items!!
            .filterIsInstance<ExplorerItem.Shortcut>()
            .single { it.shortcutId == "network" }
        return shortcut.subtitle?.get(countingContext)
    }

    @Test
    fun `the network shortcut counts SMB and SFTP locations together`() = runTest {
        val smb = MockDataProvider.createMockStorageNetwork().location
        val sftp = MockDataProvider.createMockStorageSftp().location

        loader(listOf(smb, sftp)).networkSubtitle() shouldBe "2"
    }

    @Test
    fun `an SFTP-only setup still counts`() = runTest {
        loader(listOf(MockDataProvider.createMockStorageSftp().location)).networkSubtitle() shouldBe "1"
    }

    @Test
    fun `no locations count as zero`() = runTest {
        loader(emptyList()).networkSubtitle() shouldBe "0"
    }
}
