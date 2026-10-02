package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class ExplorerNetworkLocationControllerTest : BaseTest() {

    private val smbItem = MockDataProvider.createMockStorageNetwork()
    private val sftpItem = MockDataProvider.createMockStorageSftp()

    private fun dialogs() = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private val errors = mutableListOf<Throwable>()
    private var selectionCleared = 0

    private fun CoroutineScope.controller(
        smbLocationManager: SmbLocationManager = mockk<SmbLocationManager>().apply {
            coEvery { delete(any()) } just Runs
        },
        sftpLocationManager: SftpLocationManager = mockk<SftpLocationManager>().apply {
            coEvery { delete(any()) } just Runs
        },
        dialogs: ExplorerDialogController = dialogs(),
        workspace: ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
            coEvery { navigate(any()) } just Runs
        },
        currentLocation: ExplorerLocation? = mockk<ExplorerLocation.Network>(),
    ) = ExplorerNetworkLocationController(
        smbLocationManager = smbLocationManager,
        sftpLocationManager = sftpLocationManager,
        dialogs = dialogs,
        workspace = { workspace },
        currentLocation = { currentLocation },
        clearSelection = { selectionCleared++ },
        onError = { errors.add(it) },
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `removal is confirmed first`() = runTest {
        val dialogs = dialogs()

        controller(dialogs = dialogs).showRemoveConfirmation(listOf(sftpItem))

        dialogs.current() shouldBe ExplorerDialogState.RemoveLocationConfirmation(listOf(sftpItem))
    }

    @Test
    fun `a confirmed SFTP removal deletes through the SFTP manager only`() = runTest {
        val smb = mockk<SmbLocationManager>()
        val sftp = mockk<SftpLocationManager>().apply { coEvery { delete(any()) } just Runs }
        val dialogs = dialogs().apply { show(ExplorerDialogState.RemoveLocationConfirmation(listOf(sftpItem))) }

        controller(smbLocationManager = smb, sftpLocationManager = sftp, dialogs = dialogs)
            .onRemoveConfirmed(listOf(sftpItem))
        advanceUntilIdle()

        coVerify(exactly = 1) { sftp.delete(sftpItem.location.id) }
        coVerify(exactly = 0) { smb.delete(any()) }
        dialogs.current() shouldBe ExplorerDialogState.None
        selectionCleared shouldBe 1
    }

    @Test
    fun `a mixed selection deletes each location through its own manager`() = runTest {
        val smb = mockk<SmbLocationManager>().apply { coEvery { delete(any()) } just Runs }
        val sftp = mockk<SftpLocationManager>().apply { coEvery { delete(any()) } just Runs }

        controller(smbLocationManager = smb, sftpLocationManager = sftp).onRemoveConfirmed(listOf(smbItem, sftpItem))
        advanceUntilIdle()

        coVerify(exactly = 1) { smb.delete(smbItem.location.id) }
        coVerify(exactly = 1) { sftp.delete(sftpItem.location.id) }
        coVerify(exactly = 0) { smb.delete(sftpItem.location.id) }
        coVerify(exactly = 0) { sftp.delete(smbItem.location.id) }
    }

    @Test
    fun `a failed removal is reported`() = runTest {
        val failure = IllegalStateException("database gone")
        val sftp = mockk<SftpLocationManager>().apply { coEvery { delete(any<Uuid>()) } throws failure }

        controller(sftpLocationManager = sftp).onRemoveConfirmed(listOf(sftpItem))
        advanceUntilIdle()

        errors shouldBe listOf(failure)
        selectionCleared shouldBe 1
    }

    @Test
    fun `the live Network list is not reloaded, anything else is`() = runTest {
        val onNetwork = mockk<ExplorerWorkspace>().apply { coEvery { navigate(any()) } just Runs }
        controller(workspace = onNetwork).onRemoveConfirmed(listOf(sftpItem))
        advanceUntilIdle()
        coVerify(exactly = 0) { onNetwork.navigate(any()) }

        val elsewhere = mockk<ExplorerWorkspace>().apply { coEvery { navigate(any()) } just Runs }
        controller(workspace = elsewhere, currentLocation = null).onRemoveConfirmed(listOf(sftpItem))
        advanceUntilIdle()
        coVerify(exactly = 1) { elsewhere.navigate(ExplorerNavigation.Refresh) }
    }

    /** Adding is free: a free user gets the chooser like everyone else. */
    @Test
    fun `adding asks every user for the protocol first`() = runTest {
        val dialogs = dialogs()

        controller(dialogs = dialogs).showAddChooser()

        dialogs.current() shouldBe ExplorerDialogState.NetworkProtocolChooser
    }
}
