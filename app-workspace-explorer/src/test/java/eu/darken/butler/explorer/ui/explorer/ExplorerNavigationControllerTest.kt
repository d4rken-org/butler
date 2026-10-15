package eu.darken.butler.explorer.ui.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Lan
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.network.NetworkCredentialAvailability
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.network.NetworkLocation
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class ExplorerNavigationControllerTest : BaseTest() {

    private val workspaceId = eu.darken.butler.workspace.core.Workspace.Id()

    private fun path(name: String) = LocalPath.build(File("/tmp/nav-test", name))

    private fun directoryItem(name: String): ExplorerItem.RegularDirectory {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns path(name)
        }
        return ExplorerItem.RegularDirectory(lookup = lookup)
    }

    private fun fileItem(
        name: String,
        mime: String = "text/plain",
        at: APath<*> = path(name),
    ): ExplorerItem.RegularFile {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns at
            every { this@apply.path } returns at.path
            every { this@apply.name } returns name
        }
        return ExplorerItem.RegularFile(lookup = lookup, mimeType = MimeInfo(mime))
    }

    private fun symlinkItem(name: String, mime: String, target: APath<*>): ExplorerItem.SymbolicLink {
        val linkPath = path(name)
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns linkPath
            every { this@apply.path } returns linkPath.path
            every { this@apply.name } returns name
            every { this@apply.target } returns target
        }
        return ExplorerItem.SymbolicLink(lookup = lookup, mimeType = MimeInfo(mime), targetPath = target)
    }

    private fun gatewaySwitch(targetType: FileType): GatewaySwitch = mockk<GatewaySwitch>().apply {
        val targetLookup = mockk<APathLookup<APath<*>>>().apply {
            every { fileType } returns targetType
        }
        coEvery { lookup(any(), any()) } returns targetLookup
    }

    private fun networkItem(
        status: ExplorerItem.Storage.Network.Status = ExplorerItem.Storage.Network.Status.AVAILABLE,
    ): ExplorerItem.Storage.Network {
        val location = SmbLocation(
            id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
            label = "Home NAS",
            host = "nas.local",
            share = "media",
            authType = SmbLocation.AuthType.PASSWORD,
            rememberCredential = true,
            credentialVersion = 1,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
        return ExplorerItem.Storage.Network(
            location = NetworkLocation.Smb(location),
            displayName = "Home NAS".toCaString(),
            displayIcon = Icons.TwoTone.Lan,
            target = ExplorerNavigation.Target.Directory(location.rootPath),
            subtitle = location.endpointLabel.toCaString(),
            credentials = when (status) {
                ExplorerItem.Storage.Network.Status.AVAILABLE -> NetworkCredentialAvailability.AVAILABLE
                ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED -> NetworkCredentialAvailability.MISSING
            },
        )
    }

    private fun localItem() = ExplorerItem.Storage.Local(
        localId = "primary",
        displayName = "Internal storage".toCaString(),
        displayIcon = Icons.TwoTone.Lan,
        target = ExplorerNavigation.Target.Directory(path("storage")),
    )

    private fun homeLocation(): ExplorerLocation.Home = mockk<ExplorerLocation.Home>().apply {
        every { locationId } returns "home"
        every { progress } returns null
        every { info } returns null
    }

    private fun mockWorkspace(): ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
        coEvery { navigate(any()) } just Runs
        every { pickerConfig } returns null
    }

    private fun dialogs() = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private var lockedHints = 0
    private val sftpForms = mutableListOf<SftpLocation>()
    private val webDavForms = mutableListOf<WebDavLocation>()
    private val openedInViewer = mutableListOf<ExplorerItem.File>()
    private val openedExternally = mutableListOf<ExplorerItem.File>()

    private fun CoroutineScope.controller(
        workspace: ExplorerWorkspace = mockWorkspace(),
        dialogs: ExplorerDialogController = dialogs(),
        favoritesRepo: ExplorerFavoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
            coEvery { refresh() } just Runs
        },
        selectedItems: () -> Set<ExplorerItem> = { emptySet() },
        clearSelection: () -> Unit = {},
        state: ExplorerWorkspaceViewModel.State = ExplorerWorkspaceViewModel.State(),
        upgradeRepo: UpgradeRepo = FakeUpgradeRepo(pro = true),
        workspaceRemote: WorkspaceRemote = mockk(),
        gatewaySwitch: GatewaySwitch = mockk(),
        openOnTap: Boolean = false,
        externalHandOff: Boolean = true,
        openInViewerGate: CompletableDeferred<Unit>? = null,
        timeSource: TimeSource = TimeSource.Monotonic,
    ) = ExplorerNavigationController(
        workspaceId = workspaceId,
        workspace = { workspace },
        workspaceRemote = workspaceRemote,
        gatewaySwitch = gatewaySwitch,
        dialogs = dialogs,
        favoritesRepo = favoritesRepo,
        upgradeRepo = upgradeRepo,
        onNetworkLocked = { lockedHints++ },
        showSftpLocationForm = { sftpForms.add(it) },
        showWebDavLocationForm = { webDavForms.add(it) },
        selectedItems = selectedItems,
        toggleSelection = {},
        clearSelection = clearSelection,
        getState = { state },
        isOpenOnTapEnabled = { openOnTap },
        openInViewer = {
            openedInViewer.add(it)
            openInViewerGate?.await()
        },
        openExternally = {
            openedExternally.add(it)
            externalHandOff
        },
        timeSource = timeSource,
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `a free user tapping a network location gets the upgrade hint`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace, upgradeRepo = FakeUpgradeRepo(pro = false))

        controller.navigate(networkItem() as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 1
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    /** Signing in only leads on to browsing, which a free user cannot do anyway. */
    @Test
    fun `a free user tapping a sign-in required location gets the upgrade hint, not the form`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, upgradeRepo = FakeUpgradeRepo(pro = false))
        val item = networkItem(ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED)

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 1
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `a pro user tapping a sign-in required location gets the form`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)
        val item = networkItem(ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED)

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 0
        val form = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.SmbLocationForm>()
        form.existing shouldBe (item.location as NetworkLocation.Smb).location
    }

    @Test
    fun `a free user tapping an SFTP location gets the upgrade hint`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace, upgradeRepo = FakeUpgradeRepo(pro = false))

        controller.navigate(MockDataProvider.createMockStorageSftp() as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 1
        sftpForms shouldBe emptyList()
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `a free user tapping a sign-in required SFTP location gets the upgrade hint, not the form`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, upgradeRepo = FakeUpgradeRepo(pro = false))
        val item = MockDataProvider.createMockStorageSftp(status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED)

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 1
        sftpForms shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `a pro user tapping a sign-in required SFTP location is routed to the SFTP form, not the SMB one`() = runTest {
        val dialogs = dialogs()
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace, dialogs = dialogs)
        val item = MockDataProvider.createMockStorageSftp(status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED)

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 0
        sftpForms shouldBe listOf((item.location as NetworkLocation.Sftp).location)
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `a free user tapping a sign-in required WebDAV location gets the upgrade hint, not the form`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, upgradeRepo = FakeUpgradeRepo(pro = false))
        val item = MockDataProvider.createMockStorageWebDav(status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED)

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 1
        webDavForms shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `a pro user tapping a sign-in required WebDAV location is routed to the WebDAV form`() = runTest {
        val dialogs = dialogs()
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace, dialogs = dialogs)
        val item = MockDataProvider.createMockStorageWebDav(status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED)

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 0
        webDavForms shouldBe listOf((item.location as NetworkLocation.WebDav).location)
        sftpForms shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `a pro user tapping a WebDAV location navigates into it`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)
        val item = MockDataProvider.createMockStorageWebDav()

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 0
        webDavForms shouldBe emptyList()
        coVerify { workspace.navigate(item.target) }
    }

    @Test
    fun `a pro user tapping an SFTP location navigates into it`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)
        val item = MockDataProvider.createMockStorageSftp()

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 0
        sftpForms shouldBe emptyList()
        coVerify { workspace.navigate(item.target) }
    }

    @Test
    fun `a pro user tapping a network location navigates into it`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)
        val item = networkItem()

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 0
        coVerify { workspace.navigate(item.target) }
    }

    @Test
    fun `a free user tapping local storage navigates into it`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace, upgradeRepo = FakeUpgradeRepo(pro = false))
        val item = localItem()

        controller.navigate(item as ExplorerItem)
        runCurrent()

        lockedHints shouldBe 0
        coVerify { workspace.navigate(item.target) }
    }

    @Test
    fun `directory tap navigates and clears selection`() = runTest {
        var selectionCleared = false
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace, clearSelection = { selectionCleared = true })
        val item = directoryItem("docs")

        val expectedTarget = ExplorerNavigation.Target.Directory(item.lookup.lookedUp)
        controller.navigate(item as ExplorerItem)
        runCurrent()

        coVerify { workspace.navigate(expectedTarget) }
        selectionCleared shouldBe true
    }

    @Test
    fun `file tap without picker shows the file options dialog`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)
        val item = fileItem("report.pdf")

        controller.navigate(item as ExplorerItem)
        runCurrent()

        val dialog = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>()
        dialog.item shouldBe item
    }

    @Test
    fun `a zip tap browses into the archive`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)
        val item = fileItem("photos.zip")

        val expectedTarget = ExplorerNavigation.Target.Directory(ArchivePath.root(item.lookup.lookedUp))
        controller.navigate(item as ExplorerItem)
        runCurrent()

        coVerify { workspace.navigate(expectedTarget) }
    }

    @Test
    fun `an app install bundle tap opens the options sheet instead of browsing`() = runTest {
        // Bundles are zips, but browsing on tap would move Install off the primary gesture.
        listOf("app.xapk", "app.apks", "app.apkm").forEach { name ->
            val workspace = mockWorkspace()
            val dialogs = dialogs()
            val controller = controller(workspace = workspace, dialogs = dialogs)
            val item = fileItem(name)

            controller.navigate(item as ExplorerItem)
            runCurrent()

            coVerify(exactly = 0) { workspace.navigate(any()) }
            dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>().item shouldBe item
        }
    }

    @Test
    fun `with open on tap off a file tap still shows the options sheet`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, openOnTap = false)
        val item = fileItem("photo.jpg", mime = "image/jpeg")

        controller.navigate(item as ExplorerItem)
        runCurrent()

        dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>().item shouldBe item
        openedInViewer shouldBe emptyList()
        openedExternally shouldBe emptyList()
    }

    @Test
    fun `with open on tap on types the viewer renders open in the viewer`() = runTest {
        listOf(
            fileItem("photo.jpg", mime = "image/jpeg"),
            fileItem("invoice.pdf", mime = "application/pdf"),
            fileItem("notes.txt", mime = "text/plain"),
            fileItem("app.apk", mime = MimeInfo.MIME_APK),
        ).forEach { item ->
            openedInViewer.clear()
            val dialogs = dialogs()
            val controller = controller(dialogs = dialogs, openOnTap = true)

            controller.navigate(item as ExplorerItem)
            runCurrent()

            openedInViewer shouldBe listOf(item)
            openedExternally shouldBe emptyList()
            dialogs.current() shouldBe ExplorerDialogState.None
        }
    }

    @Test
    fun `with open on tap a second tap while the first open is running does not open the file twice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = controller(openOnTap = true, openInViewerGate = gate, timeSource = testTimeSource)
        val item = fileItem("photo.jpg", mime = "image/jpeg")

        controller.navigate(item as ExplorerItem)
        controller.navigate(item as ExplorerItem)
        runCurrent()

        openedInViewer shouldBe listOf(item)

        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `with open on tap a tap after the first open finished opens the file again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = controller(openOnTap = true, openInViewerGate = gate, timeSource = testTimeSource)
        val item = fileItem("photo.jpg", mime = "image/jpeg")

        controller.navigate(item as ExplorerItem)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(600.milliseconds)
        controller.navigate(item as ExplorerItem)
        runCurrent()

        openedInViewer shouldBe listOf(item, item)
    }

    @Test
    fun `with open on tap a second tap right after a hand-off does not hand the file off again`() = runTest {
        val controller = controller(openOnTap = true, externalHandOff = true, timeSource = testTimeSource)
        val item = fileItem("clip.mp4", mime = "video/mp4")

        controller.navigate(item as ExplorerItem)
        runCurrent()
        controller.navigate(item as ExplorerItem)
        runCurrent()

        openedExternally shouldBe listOf(item)
    }

    @Test
    fun `with open on tap a tap after the cooldown hands the file off again`() = runTest {
        val controller = controller(openOnTap = true, externalHandOff = true, timeSource = testTimeSource)
        val item = fileItem("clip.mp4", mime = "video/mp4")

        controller.navigate(item as ExplorerItem)
        runCurrent()
        advanceTimeBy(600.milliseconds)
        controller.navigate(item as ExplorerItem)
        runCurrent()

        openedExternally shouldBe listOf(item, item)
    }

    @Test
    fun `with open on tap a different file tapped right after an open still opens`() = runTest {
        val controller = controller(openOnTap = true, timeSource = testTimeSource)
        val first = fileItem("photo.jpg", mime = "image/jpeg")
        val second = fileItem("notes.txt", mime = "text/plain")

        controller.navigate(first as ExplorerItem)
        runCurrent()
        controller.navigate(second as ExplorerItem)
        runCurrent()

        openedInViewer shouldBe listOf(first, second)
    }

    @Test
    fun `with open on tap on an app install bundle opens in the viewer`() = runTest {
        listOf("app.xapk", "app.apks", "app.apkm").forEach { name ->
            openedInViewer.clear()
            val workspace = mockWorkspace()
            val dialogs = dialogs()
            val controller = controller(workspace = workspace, dialogs = dialogs, openOnTap = true)
            val item = fileItem(name, mime = "application/zip")

            controller.navigate(item as ExplorerItem)
            runCurrent()

            openedInViewer shouldBe listOf(item)
            openedExternally shouldBe emptyList()
            dialogs.current() shouldBe ExplorerDialogState.None
            coVerify(exactly = 0) { workspace.navigate(any()) }
        }
    }

    @Test
    fun `with open on tap on a zip tap still browses into the archive`() = runTest {
        val workspace = mockWorkspace()
        val dialogs = dialogs()
        val controller = controller(workspace = workspace, dialogs = dialogs, openOnTap = true)
        val item = fileItem("photos.zip", mime = "application/zip")
        val expectedTarget = ExplorerNavigation.Target.Directory(ArchivePath.root(item.lookup.lookedUp))

        controller.navigate(item as ExplorerItem)
        runCurrent()

        coVerify { workspace.navigate(expectedTarget) }
        openedInViewer shouldBe emptyList()
        openedExternally shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `with open on tap on a symlink to a folder still navigates into it`() = runTest {
        val workspace = mockWorkspace()
        val target = path("real-folder")
        val controller = controller(
            workspace = workspace,
            gatewaySwitch = gatewaySwitch(FileType.DIRECTORY),
            openOnTap = true,
        )
        val item = symlinkItem("folder-link", mime = "application/octet-stream", target = target)

        controller.navigate(item as ExplorerItem)
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Target.Directory(target)) }
        openedInViewer shouldBe emptyList()
        openedExternally shouldBe emptyList()
    }

    @Test
    fun `with open on tap on a symlink to an unsupported file is handed to another app`() = runTest {
        val dialogs = dialogs()
        val controller = controller(
            dialogs = dialogs,
            gatewaySwitch = gatewaySwitch(FileType.FILE),
            openOnTap = true,
        )
        val item = symlinkItem("clip-link.mp4", mime = "video/mp4", target = path("clip.mp4"))

        controller.navigate(item as ExplorerItem)
        runCurrent()

        openedExternally shouldBe listOf(item)
        openedInViewer shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `with open on tap on an unsupported local file is handed to another app`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, openOnTap = true, externalHandOff = true)
        val item = fileItem("clip.mp4", mime = "video/mp4")

        controller.navigate(item as ExplorerItem)
        runCurrent()

        openedExternally shouldBe listOf(item)
        openedInViewer shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `with open on tap on a failed hand-off falls back to the options sheet`() = runTest {
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, openOnTap = true, externalHandOff = false)
        val item = fileItem("clip.mp4", mime = "video/mp4")

        controller.navigate(item as ExplorerItem)
        runCurrent()

        openedExternally shouldBe listOf(item)
        val sheet = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>()
        sheet.item shouldBe item
        sheet.showInFolder shouldBe false
    }

    @Test
    fun `with open on tap on an unsupported file that cannot be handed off shows the options sheet`() = runTest {
        listOf<APath<*>>(
            SftpPath(sftpLocationId, listOf("media", "clip.mp4")),
            ArchivePath(path("media.zip"), listOf("clip.mp4")),
        ).forEach { location ->
            val dialogs = dialogs()
            val controller = controller(dialogs = dialogs, openOnTap = true)
            val item = fileItem("clip.mp4", mime = "video/mp4", at = location)

            controller.navigate(item as ExplorerItem)
            runCurrent()

            openedExternally shouldBe emptyList()
            openedInViewer shouldBe emptyList()
            dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>().item shouldBe item
        }
    }

    @Test
    fun `with open on tap on the fallback sheet in Recent offers show in folder`() = runTest {
        val dialogs = dialogs()
        val controller = controller(
            dialogs = dialogs,
            state = ExplorerWorkspaceViewModel.State(currentLocation = ExplorerLocation.Recent()),
            openOnTap = true,
            externalHandOff = false,
        )
        val item = fileItem("clip.mp4", mime = "video/mp4")

        controller.navigate(item as ExplorerItem)
        runCurrent()

        val sheet = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>()
        sheet.item shouldBe item
        sheet.showInFolder shouldBe true
    }

    @Test
    fun `with open on tap on a file-single picker still selects the file instantly`() = runTest {
        val config = PickerConfig(callerWorkspaceId = Workspace.Id(), selection = PickerConfig.Selection.FileSingle)
        val workspace = mockWorkspace().apply {
            every { pickerConfig } returns config
        }
        val workspaceRemote = mockk<WorkspaceRemote>(relaxed = true)
        val dialogs = dialogs()
        val controller = controller(
            workspace = workspace,
            dialogs = dialogs,
            workspaceRemote = workspaceRemote,
            openOnTap = true,
        )
        val item = fileItem("photo.jpg", mime = "image/jpeg")
        val expectedPath = item.lookup.lookedUp

        controller.navigate(item as ExplorerItem)
        runCurrent()

        coVerify {
            workspaceRemote.emitEvent(
                WorkspaceEvent.PickerResult(
                    workspaceId = workspaceId,
                    callerWorkspaceId = config.callerWorkspaceId,
                    selectedPaths = listOf(expectedPath),
                )
            )
        }
        openedInViewer shouldBe emptyList()
        openedExternally shouldBe emptyList()
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `with open on tap on a directory-single picker still shows the options sheet`() = runTest {
        val config = PickerConfig(callerWorkspaceId = Workspace.Id(), selection = PickerConfig.Selection.DirectorySingle)
        val workspace = mockWorkspace().apply {
            every { pickerConfig } returns config
        }
        val dialogs = dialogs()
        val controller = controller(workspace = workspace, dialogs = dialogs, openOnTap = true)
        val item = fileItem("photo.jpg", mime = "image/jpeg")

        controller.navigate(item as ExplorerItem)
        runCurrent()

        dialogs.current().shouldBeInstanceOf<ExplorerDialogState.FileOptions>().item shouldBe item
        openedInViewer shouldBe emptyList()
        openedExternally shouldBe emptyList()
    }

    @Test
    fun `refresh re-resolves favorites BEFORE re-navigating`() = runTest {
        // This is the ordering contract from the checkpoint list: favorites must be
        // refreshed first so re-granted/remounted locations resolve in the new listing.
        val workspace = mockWorkspace()
        val favoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
            coEvery { refresh() } just Runs
        }
        val controller = controller(workspace = workspace, favoritesRepo = favoritesRepo)

        controller.retryNavigation()
        runCurrent()

        coVerifyOrder {
            favoritesRepo.refresh()
            workspace.navigate(ExplorerNavigation.Refresh)
        }
    }

    @Test
    fun `reveal emits a request and highlights the items`() = runTest {
        val controller = controller()
        val received = mutableListOf<ExplorerWorkspaceViewModel.RevealRequest>()
        val collector = launch { controller.revealRequests.collect { received.add(it) } }
        runCurrent()

        val target = path("show-me.txt")
        controller.revealItems(listOf(target))
        runCurrent()

        received.map { it.path.path } shouldBe listOf(target.path)
        received.first().highlight shouldBe true
        received.first().scope shouldBe ExplorerWorkspaceViewModel.RevealRequest.Scope.Items
        controller.highlightedItemIds.value shouldBe setOf(target.path)

        controller.clearHighlights("some-other-location")
        controller.highlightedItemIds.value shouldBe emptySet()

        collector.cancel()
    }

    @Test
    fun `highlights survive the arrival event of the location they belong to`() = runTest {
        val location = homeLocation()
        val controller = controller(state = ExplorerWorkspaceViewModel.State(currentLocation = location))
        val collector = launch { controller.revealRequests.collect { } }
        runCurrent()

        controller.revealItems(listOf(path("fav")))
        runCurrent()
        controller.highlightedItemIds.value shouldBe setOf(path("fav").path)

        // The location change that brought us here must not wipe the highlight set for it.
        controller.clearHighlights("home")
        controller.highlightedItemIds.value shouldBe setOf(path("fav").path)

        // Leaving does clear it.
        controller.clearHighlights("elsewhere")
        controller.highlightedItemIds.value shouldBe emptySet()

        collector.cancel()
    }

    @Test
    fun `revealFavorite navigates home and reveals in the favorites section`() = runTest {
        val directory = mockk<ExplorerLocation.Directory>().apply {
            every { locationId } returns "some-dir"
            every { progress } returns null
            every { info } returns null
        }
        val home = homeLocation()
        val homeState = mockk<ExplorerWorkspace.State.Ready>().apply {
            every { currentLocation } returns home
        }
        val workspace = mockWorkspace().apply {
            every { state } returns flowOf(homeState)
        }
        // The combined UI state deliberately lags behind the workspace state here: the highlight
        // must be attributed to the location we actually arrived at, not to this stale one.
        val controller = controller(
            workspace = workspace,
            state = ExplorerWorkspaceViewModel.State(currentLocation = directory),
        )
        val received = mutableListOf<ExplorerWorkspaceViewModel.RevealRequest>()
        val collector = launch { controller.revealRequests.collect { received.add(it) } }
        runCurrent()

        val target = path("Pictures")
        launch { controller.revealFavorite(target) }
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Target.Home) }
        received.single().path.path shouldBe target.path
        received.single().scope shouldBe ExplorerWorkspaceViewModel.RevealRequest.Scope.Favorites
        controller.highlightedItemIds.value shouldBe setOf(target.path)

        // The arrival event for Home must not wipe the highlight we just installed for it.
        controller.clearHighlights("home")
        controller.highlightedItemIds.value shouldBe setOf(target.path)

        collector.cancel()
    }

    @Test
    fun `show in folder waits for the finished listing, not the peek`() = runTest {
        val parent = path("Documents")
        val target = parent.child("report.pdf")

        val workspaceState = MutableStateFlow<ExplorerWorkspace.State>(ExplorerWorkspace.State.Initializing)
        val workspace = mockWorkspace().apply {
            every { state } returns workspaceState
        }
        val controller = controller(workspace = workspace)
        val received = mutableListOf<ExplorerWorkspaceViewModel.RevealRequest>()
        val collector = launch { controller.revealRequests.collect { received.add(it) } }
        runCurrent()

        controller.showInFolder(target)
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Target.Directory(parent)) }

        // The peek pass publishes items while the load is still running: the rows are placeholders
        // and the listing is not final, so a scroll index resolved against it points at the wrong
        // row. The reveal has to keep waiting.
        workspaceState.value = ExplorerWorkspace.State.Ready(
            currentLocation = ExplorerLocation.Directory(
                path = parent,
                items = listOf(ExplorerItem.Peek(target)),
                progress = Progress.Data(),
            ),
        )
        runCurrent()

        received shouldBe emptyList()

        workspaceState.value = ExplorerWorkspace.State.Ready(
            currentLocation = ExplorerLocation.Directory(
                path = parent,
                items = listOf(fileItem("report.pdf")),
                progress = null,
            ),
        )
        runCurrent()

        received.single().path.path shouldBe target.path
        received.single().highlight shouldBe true

        collector.cancel()
    }

    @Test
    fun `going back reveals the previous directory without highlighting`() = runTest {
        val current = path("current-dir")
        val location = mockk<ExplorerLocation.Directory>().apply {
            every { this@apply.path } returns current
            every { progress } returns null
            every { info } returns null
        }
        val workspace = mockWorkspace()
        val controller = controller(
            workspace = workspace,
            state = ExplorerWorkspaceViewModel.State(currentLocation = location),
        )
        val received = mutableListOf<ExplorerWorkspaceViewModel.RevealRequest>()
        val collector = launch { controller.revealRequests.collect { received.add(it) } }
        runCurrent()

        controller.goBack()
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Back) }
        received.single().highlight shouldBe false
        controller.highlightedItemIds.value shouldBe emptySet()

        collector.cancel()
    }

    @Test
    fun `dismissing a navigation error goes back when possible otherwise home`() = runTest {
        val backWorkspace = mockWorkspace()
        controller(workspace = backWorkspace, state = ExplorerWorkspaceViewModel.State(canGoBack = true))
            .dismissNavigationError()
        runCurrent()
        coVerify { backWorkspace.navigate(ExplorerNavigation.Back) }

        val homeWorkspace = mockWorkspace()
        controller(workspace = homeWorkspace, state = ExplorerWorkspaceViewModel.State(canGoBack = false))
            .dismissNavigationError()
        runCurrent()
        coVerify { homeWorkspace.navigate(ExplorerNavigation.Target.Home) }
    }

    @Test
    fun `edited local path navigates to the rebuilt absolute path`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(LocalPath.build("/storage/emulated/0"), "storage/Documents ")
        runCurrent()

        coVerify {
            workspace.navigate(
                ExplorerNavigation.Target.Directory(LocalPath.build("/storage/Documents")),
            )
        }
    }

    private val sftpLocationId = Uuid.parse("66666666-7777-8888-9999-000000000000")

    @Test
    fun `an edited SFTP path is relative to the location root`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(SftpPath(sftpLocationId, listOf("srv")), "/builds//2026:rc*/")
        runCurrent()

        coVerify {
            workspace.navigate(
                ExplorerNavigation.Target.Directory(SftpPath(sftpLocationId, listOf("builds", "2026:rc*"))),
            )
        }
    }

    @Test
    fun `a backslash in an edited SFTP path is part of the name`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(SftpPath.root(sftpLocationId), "a\\b/c")
        runCurrent()

        coVerify {
            workspace.navigate(ExplorerNavigation.Target.Directory(SftpPath(sftpLocationId, listOf("a\\b", "c"))))
        }
    }

    @Test
    fun `an edited SFTP path cannot climb out of the location`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(SftpPath(sftpLocationId, listOf("srv")), "../etc")
        controller.navigateToEditedPath(SftpPath(sftpLocationId, listOf("srv")), "./srv")
        runCurrent()

        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `an empty edited SFTP path goes to the location root`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(SftpPath(sftpLocationId, listOf("srv")), "/")
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Target.Directory(SftpPath.root(sftpLocationId))) }
    }

    @Test
    fun `spaces in an edited SFTP path are part of the names`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(SftpPath.root(sftpLocationId), "/ /draft ")
        runCurrent()

        coVerify {
            workspace.navigate(ExplorerNavigation.Target.Directory(SftpPath(sftpLocationId, listOf(" ", "draft "))))
        }
    }

    private val webdavLocationId = Uuid.parse("77777777-8888-9999-0000-111111111111")

    @Test
    fun `an edited WebDAV path is relative to the location root`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(WebDavPath(webdavLocationId, listOf("docs")), "/photos//100% ?#done/ a\\b ")
        runCurrent()

        coVerify {
            workspace.navigate(
                ExplorerNavigation.Target.Directory(
                    WebDavPath(webdavLocationId, listOf("photos", "100% ?#done", " a\\b ")),
                ),
            )
        }
    }

    @Test
    fun `an edited WebDAV path cannot climb out of the location`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(WebDavPath(webdavLocationId, listOf("docs")), "../other")
        controller.navigateToEditedPath(WebDavPath(webdavLocationId, listOf("docs")), "docs/./a")
        controller.navigateToEditedPath(WebDavPath(webdavLocationId, listOf("docs")), "a\u0000b")
        runCurrent()

        coVerify(exactly = 0) { workspace.navigate(any()) }
    }

    @Test
    fun `an empty edited WebDAV path goes to the location root`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(workspace = workspace)

        controller.navigateToEditedPath(WebDavPath(webdavLocationId, listOf("docs")), "/")
        runCurrent()

        coVerify { workspace.navigate(ExplorerNavigation.Target.Directory(WebDavPath.root(webdavLocationId))) }
    }
}
