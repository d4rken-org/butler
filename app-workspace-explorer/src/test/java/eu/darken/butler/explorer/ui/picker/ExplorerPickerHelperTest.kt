package eu.darken.butler.explorer.ui.picker

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class ExplorerPickerHelperTest : BaseTest() {

    private lateinit var helper: ExplorerPickerHelper

    @BeforeEach
    fun setup() {
        helper = ExplorerPickerHelper()
    }

    // ═══════════════════════════════════════════════════════════════
    // Test Fixtures
    // ═══════════════════════════════════════════════════════════════

    private fun mockDirectory(path: String = "/sdcard/test", canWrite: Boolean? = null): ExplorerItem.RegularDirectory {
        val aPath = LocalPath.build(path)
        val lookup = mockk<APathLookup<*>> {
            every { lookedUp } returns aPath
            every { size } returns 4096L
        }
        return mockk<ExplorerItem.RegularDirectory> {
            every { this@mockk.lookup } returns lookup
            every { this@mockk.childCount } returns null
            every { this@mockk.canWrite } returns canWrite
        }
    }

    private fun mockFile(path: String = "/sdcard/test.txt"): ExplorerItem.RegularFile {
        val aPath = LocalPath.build(path)
        val lookup = mockk<APathLookup<*>> {
            every { lookedUp } returns aPath
            every { size } returns 1024L
        }
        return mockk<ExplorerItem.RegularFile> {
            every { this@mockk.lookup } returns lookup
            every { mimeType } returns MimeInfo("text/plain")
            every { canWrite } returns null
        }
    }

    private fun mockStorage(
        path: String = "/storage/emulated/0",
        canWrite: Boolean? = true
    ): ExplorerItem.Storage.Local {
        val aPath = LocalPath.build(path)
        val target = mockk<ExplorerNavigation.Target.Directory> {
            every { this@mockk.path } returns aPath
        }
        return mockk<ExplorerItem.Storage.Local> {
            every { displayName } returns "Internal Storage".toCaString()
            every { this@mockk.target } returns target
            every { this@mockk.canWrite } returns canWrite
        }
    }

    private fun mockDirectoryLocation(
        path: String = "/sdcard",
        isWritable: Boolean = true
    ): ExplorerLocation.Directory {
        val aPath = LocalPath.build(path)
        val info = mockk<ExplorerLocation.Directory.Info> {
            every { this@mockk.isWritable } returns isWritable
        }
        return mockk<ExplorerLocation.Directory> {
            every { this@mockk.path } returns aPath
            every { this@mockk.info } returns info
        }
    }

    private fun mockDeviceLocation(): ExplorerLocation.Device {
        return mockk<ExplorerLocation.Device>()
    }

    private fun mockNetworkLocation(): ExplorerLocation.Network = mockk<ExplorerLocation.Network>()

    private fun mockNetworkStorage(
        status: ExplorerItem.Storage.Network.Status = ExplorerItem.Storage.Network.Status.AVAILABLE,
        id: Uuid = Uuid.parse("11111111-2222-3333-4444-555555555555"),
    ): ExplorerItem.Storage.Network = MockDataProvider.createMockStorageNetwork(status = status, id = id)

    // ═══════════════════════════════════════════════════════════════
    // canConfirmSelection Tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class CanConfirmSelection {

        @Test
        fun `returns true when not in picker mode`() {
            helper.canConfirmSelection(
                config = null,
                currentLocation = mockDirectoryLocation(),
                selectedItems = emptySet(),
                saveAsFilename = "",
            ) shouldBe true
        }

        @Nested
        inner class DirectorySingle {

            @Test
            fun `enabled when at writable directory`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(isWritable = true),
                    selectedItems = emptySet(),
                    saveAsFilename = "",
                ) shouldBe true
            }

            @Test
            fun `disabled when at non-writable directory`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                    requireWritable = true,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(isWritable = false),
                    selectedItems = emptySet(),
                    saveAsFilename = "",
                ) shouldBe false
            }

            @Test
            fun `enabled when writable storage selected at device level`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDeviceLocation(),
                    selectedItems = setOf(mockStorage(canWrite = true)),
                    saveAsFilename = "",
                ) shouldBe true
            }

            @Test
            fun `enabled when an available network location is selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockNetworkLocation(),
                    selectedItems = setOf(mockNetworkStorage()),
                    saveAsFilename = "",
                ) shouldBe true
            }

            @Test
            fun `disabled at the network overview without a selection`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockNetworkLocation(),
                    selectedItems = emptySet(),
                    saveAsFilename = "",
                ) shouldBe false
            }

            @Test
            fun `disabled when the selected network location needs a sign-in`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockNetworkLocation(),
                    selectedItems = setOf(
                        mockNetworkStorage(ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED)
                    ),
                    saveAsFilename = "",
                ) shouldBe false
            }

            @Test
            fun `the selected network location is what gets returned`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                val item = mockNetworkStorage()

                helper.extractSelectedPaths(
                    config = config,
                    currentLocation = mockNetworkLocation(),
                    selectedItems = setOf(item),
                ) shouldBe listOf(item.target.path)
            }

            @Test
            fun `disabled when non-writable storage selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                    requireWritable = true,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDeviceLocation(),
                    selectedItems = setOf(mockStorage(canWrite = false)),
                    saveAsFilename = "",
                ) shouldBe false
            }

            @Test
            fun `disabled at device level with no selection`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDeviceLocation(),
                    selectedItems = emptySet(),
                    saveAsFilename = "",
                ) shouldBe false
            }
        }

        @Nested
        inner class SaveAs {

            @Test
            fun `enabled when filename provided and at writable directory`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.SaveAs("document.pdf"),
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(isWritable = true),
                    selectedItems = emptySet(),
                    saveAsFilename = "my_file.pdf",
                ) shouldBe true
            }

            @Test
            fun `disabled when filename is blank`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.SaveAs("document.pdf"),
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(isWritable = true),
                    selectedItems = emptySet(),
                    saveAsFilename = "",
                ) shouldBe false
            }

            @Test
            fun `disabled when directory is not writable`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.SaveAs("document.pdf"),
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(isWritable = false),
                    selectedItems = emptySet(),
                    saveAsFilename = "my_file.pdf",
                ) shouldBe false
            }

            @Test
            fun `writable files stay interactive so their name can prefill the field`() {
                val disabled = helper.computeDisabledItems(
                    items = listOf(mockFile(), mockDirectory()),
                    config = PickerConfig(
                        callerWorkspaceId = mockk(),
                        selection = PickerConfig.Selection.SaveAs("document.pdf"),
                    ),
                )
                disabled.shouldBeEmpty()
            }
        }

        @Nested
        inner class FileMulti {

            @Test
            fun `enabled when files selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.FileMulti,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = setOf(mockFile()),
                    saveAsFilename = "",
                ) shouldBe true
            }

            @Test
            fun `disabled when no files selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.FileMulti,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = emptySet(),
                    saveAsFilename = "",
                ) shouldBe false
            }
        }

        @Nested
        inner class MultiSelect {

            @Test
            fun `blocked when a selected network location needs a sign-in`() {
                listOf(
                    PickerConfig.Selection.DirectoryMulti,
                    PickerConfig.Selection.MixedMulti,
                ).forEach { selection ->
                    val config = PickerConfig(callerWorkspaceId = mockk(), selection = selection)
                    helper.canConfirmSelection(
                        config = config,
                        currentLocation = mockNetworkLocation(),
                        selectedItems = setOf(
                            mockNetworkStorage(),
                            mockNetworkStorage(
                                status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
                                id = Uuid.parse("99999999-8888-7777-6666-555555555555"),
                            ),
                        ),
                        saveAsFilename = "",
                    ) shouldBe false
                }
            }

            @Test
            fun `enabled when every selected network location is available`() {
                listOf(
                    PickerConfig.Selection.DirectoryMulti,
                    PickerConfig.Selection.MixedMulti,
                ).forEach { selection ->
                    val config = PickerConfig(callerWorkspaceId = mockk(), selection = selection)
                    helper.canConfirmSelection(
                        config = config,
                        currentLocation = mockNetworkLocation(),
                        selectedItems = setOf(
                            mockNetworkStorage(),
                            mockNetworkStorage(
                                id = Uuid.parse("99999999-8888-7777-6666-555555555555"),
                            ),
                        ),
                        saveAsFilename = "",
                    ) shouldBe true
                }
            }
        }

        @Nested
        inner class FileSingle {

            @Test
            fun `always returns false - uses instant selection`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.FileSingle,
                )
                helper.canConfirmSelection(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = setOf(mockFile()),
                    saveAsFilename = "",
                ) shouldBe false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // extractSelectedPaths Tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ExtractSelectedPaths {

        @Nested
        inner class DirectorySingle {

            @Test
            fun `returns current directory when nothing selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                val location = mockDirectoryLocation("/sdcard/Download")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = location,
                    selectedItems = emptySet(),
                )

                paths.size shouldBe 1
                paths[0].path shouldBe "/sdcard/Download"
            }

            @Test
            fun `returns selected storage path when storage selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectorySingle,
                )
                val storage = mockStorage("/storage/emulated/0")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = mockDeviceLocation(),
                    selectedItems = setOf(storage),
                )

                paths.size shouldBe 1
                paths[0].path shouldBe "/storage/emulated/0"
            }
        }

        @Nested
        inner class FileMulti {

            @Test
            fun `returns selected file paths`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.FileMulti,
                )
                val file1 = mockFile("/sdcard/doc1.pdf")
                val file2 = mockFile("/sdcard/doc2.pdf")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = setOf(file1, file2),
                )

                paths.size shouldBe 2
                paths.map { it.path }.toSet() shouldBe setOf("/sdcard/doc1.pdf", "/sdcard/doc2.pdf")
            }

            @Test
            fun `filters out directories - only files`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.FileMulti,
                )
                val file = mockFile("/sdcard/doc.pdf")
                val dir = mockDirectory("/sdcard/folder")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = setOf(file, dir),
                )

                paths.size shouldBe 1
                paths[0].path shouldBe "/sdcard/doc.pdf"
            }
        }

        @Nested
        inner class DirectoryMulti {

            @Test
            fun `returns current directory when nothing selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectoryMulti,
                )
                val location = mockDirectoryLocation("/sdcard/Documents")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = location,
                    selectedItems = emptySet(),
                )

                paths.size shouldBe 1
                paths[0].path shouldBe "/sdcard/Documents"
            }

            @Test
            fun `returns selected directories`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.DirectoryMulti,
                )
                val dir1 = mockDirectory("/sdcard/folder1")
                val dir2 = mockDirectory("/sdcard/folder2")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = setOf(dir1, dir2),
                )

                paths.size shouldBe 2
                paths.map { it.path }.toSet() shouldBe setOf("/sdcard/folder1", "/sdcard/folder2")
            }
        }

        @Nested
        inner class MixedMulti {

            @Test
            fun `returns current directory when nothing selected`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.MixedMulti,
                )
                val location = mockDirectoryLocation("/sdcard/Mixed")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = location,
                    selectedItems = emptySet(),
                )

                paths.size shouldBe 1
                paths[0].path shouldBe "/sdcard/Mixed"
            }

            @Test
            fun `returns both files and directories`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.MixedMulti,
                )
                val file = mockFile("/sdcard/doc.pdf")
                val dir = mockDirectory("/sdcard/folder")

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = setOf(file, dir),
                )

                paths.size shouldBe 2
                paths.map { it.path }.toSet() shouldBe setOf("/sdcard/doc.pdf", "/sdcard/folder")
            }
        }

        @Nested
        inner class FileSingle {

            @Test
            fun `returns empty list - uses instant selection`() {
                val config = PickerConfig(
                    callerWorkspaceId = mockk(),
                    selection = PickerConfig.Selection.FileSingle,
                )

                val paths = helper.extractSelectedPaths(
                    config = config,
                    currentLocation = mockDirectoryLocation(),
                    selectedItems = setOf(mockFile()),
                )

                paths shouldBe emptyList()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // allowsFileOpenActions Tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class AllowsFileOpenActions {

        @Test
        fun `allowed when not in picker mode`() {
            helper.allowsFileOpenActions(config = null) shouldBe true
        }

        @Test
        fun `blocked in every picker mode`() {
            val selections = listOf(
                PickerConfig.Selection.DirectorySingle,
                PickerConfig.Selection.DirectoryMulti,
                PickerConfig.Selection.FileSingle,
                PickerConfig.Selection.FileMulti,
                PickerConfig.Selection.MixedMulti,
            )

            selections.forEach { selection ->
                val config = PickerConfig(callerWorkspaceId = mockk(), selection = selection)
                helper.allowsFileOpenActions(config) shouldBe false
            }
        }

        @Test
        fun `the action bar agrees with the file options sheet`() {
            val config = PickerConfig(
                callerWorkspaceId = mockk(),
                selection = PickerConfig.Selection.FileMulti,
            )
            val item = mockFile()
            val actions = listOf(
                ExplorerActionBarItem.File.Open(item),
                ExplorerActionBarItem.File.OpenInTab(item),
                ExplorerActionBarItem.File.OpenWith(item),
                ExplorerActionBarItem.File.OpenInEditor(item),
            )

            helper.filterActionsForPicker(actions, config).shouldBeEmpty()
            helper.filterActionsForPicker(actions, config = null) shouldBe actions
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // allowsNetworkManagementActions Tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class AllowsNetworkManagementActions {

        @Test
        fun `allowed when not in picker mode`() {
            helper.allowsNetworkManagementActions(config = null) shouldBe true
        }

        @Test
        fun `blocked in picker mode`() {
            val config = PickerConfig(
                callerWorkspaceId = mockk(),
                selection = PickerConfig.Selection.DirectorySingle,
            )
            helper.allowsNetworkManagementActions(config) shouldBe false
        }

        @Test
        fun `the action bar agrees with the empty network view`() {
            val config = PickerConfig(
                callerWorkspaceId = mockk(),
                selection = PickerConfig.Selection.DirectorySingle,
            )
            val actions = listOf(
                ExplorerActionBarItem.Network.AddLocation(),
                ExplorerActionBarItem.Network.EditLocation(),
                ExplorerActionBarItem.Network.RenameLocation(),
                ExplorerActionBarItem.Network.RemoveLocation(),
            )

            helper.filterActionsForPicker(actions, config).shouldBeEmpty()
            helper.filterActionsForPicker(actions, config = null) shouldBe actions
        }
    }
}
