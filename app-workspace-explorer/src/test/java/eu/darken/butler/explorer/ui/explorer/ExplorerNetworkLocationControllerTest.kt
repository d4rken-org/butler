package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.network.NetworkLocation
import eu.darken.butler.common.files.network.NetworkLocationRepo
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerNetworkLocationControllerTest : BaseTest() {

    private val smbItem = MockDataProvider.createMockStorageNetwork()
    private val sftpItem = MockDataProvider.createMockStorageSftp()

    // Only the location matters for removal; the row's target stays the SFTP one.
    private val webDavItem = sftpItem.copy(
        location = NetworkLocation.WebDav(
            WebDavLocation(
                id = Uuid.parse("77777777-8888-9999-0000-111111111111"),
                label = null,
                url = "https://cloud.example.com/remote.php/dav/files/alice/",
                authType = WebDavLocation.AuthType.BASIC,
                username = "alice",
                rememberCredential = true,
                credentialVersion = 1,
                pinnedCertificate = null,
                trustRevision = 1,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        ),
    )

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
        webDavLocationManager: WebDavLocationManager = mockk<WebDavLocationManager>().apply {
            coEvery { delete(any()) } just Runs
        },
        locationRepo: NetworkLocationRepo = mockk<NetworkLocationRepo>().apply {
            coEvery { disconnect(any()) } just Runs
        },
        dialogs: ExplorerDialogController = dialogs(),
        workspace: ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
            coEvery { navigate(any()) } just Runs
        },
        currentLocation: ExplorerLocation? = mockk<ExplorerLocation.Network>(),
    ) = ExplorerNetworkLocationController(
        smbLocationManager = smbLocationManager,
        sftpLocationManager = sftpLocationManager,
        webDavLocationManager = webDavLocationManager,
        locationRepo = locationRepo,
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
    fun `disconnecting is confirmed first`() = runTest {
        val dialogs = dialogs()

        controller(dialogs = dialogs).showDisconnectConfirmation(listOf(smbItem, sftpItem))

        dialogs.current() shouldBe ExplorerDialogState.DisconnectLocationConfirmation(listOf(smbItem, sftpItem))
    }

    @Test
    fun `a confirmed disconnect ends every location's session`() = runTest {
        val repo = mockk<NetworkLocationRepo>().apply { coEvery { disconnect(any()) } just Runs }
        val dialogs = dialogs().apply {
            show(ExplorerDialogState.DisconnectLocationConfirmation(listOf(smbItem, sftpItem)))
        }

        controller(locationRepo = repo, dialogs = dialogs).onDisconnectConfirmed(listOf(smbItem, sftpItem))
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.disconnect(smbItem.location) }
        coVerify(exactly = 1) { repo.disconnect(sftpItem.location) }
        coVerify(exactly = 2) { repo.disconnect(any()) }
        dialogs.current() shouldBe ExplorerDialogState.None
        selectionCleared shouldBe 1
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
        val webDav = mockk<WebDavLocationManager>().apply { coEvery { delete(any()) } just Runs }

        controller(smbLocationManager = smb, sftpLocationManager = sftp, webDavLocationManager = webDav)
            .onRemoveConfirmed(listOf(smbItem, sftpItem, webDavItem))
        advanceUntilIdle()

        coVerify(exactly = 1) { smb.delete(smbItem.location.id) }
        coVerify(exactly = 1) { sftp.delete(sftpItem.location.id) }
        coVerify(exactly = 1) { webDav.delete(webDavItem.location.id) }
        coVerify(exactly = 1) { smb.delete(any()) }
        coVerify(exactly = 1) { sftp.delete(any()) }
        coVerify(exactly = 1) { webDav.delete(any()) }
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
