package eu.darken.butler.developer.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.developer.R
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.DeveloperTab
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.OptionsState
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.State
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.StorageVolumeInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.SystemInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.TargetPathInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.TestDataState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * The developer page's operations bar sits on the shared floating bar stack, which owns both the
 * bar's own geometry and the bottom content padding the four sections are laid out with.
 */
class DeveloperFloatingBarsTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val workspaceId = Workspace.Id()
    private val barCollapseStates = WorkspaceBarCollapseStates()

    private val stateSource = MutableStateFlow(systemState())
    private val operationsSource = MutableStateFlow(OperationsDisplayState())

    private fun setContent() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceBarCollapseStates provides barCollapseStates) {
                    val state by stateSource.collectAsState()
                    val operationsState by operationsSource.collectAsState()
                    DeveloperWorkspacePage(
                        workspaceId = workspaceId,
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the header cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        state = state,
                        operationsState = operationsState,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * The page composes its stack itself, so the collapse registry it writes its per-bar fractions
     * into is the read-only route to the key the bar registered under.
     */
    private fun bottomBarKeys(): Set<String> = barCollapseStates
        .snapshot()[workspaceId]
        ?.get(BarPosition.BOTTOM.persistedKey)
        ?.keys
        .orEmpty()

    private fun systemState(volumeCount: Int = 1) = State(
        id = workspaceId,
        selectedTab = DeveloperTab.SYSTEM,
        systemInfo = SystemInfo(
            deviceModel = "Pixel 8 Pro",
            deviceManufacturer = "Google",
            apiLevel = 34,
            versionName = "1.0.0-dev",
            versionCode = 100,
            flavor = "FOSS",
            buildType = "DEBUG",
            gitSha = "abc123",
            memoryAvailable = "4.2 GB",
            memoryTotal = "8.0 GB",
            storageVolumes = List(volumeCount) { index ->
                StorageVolumeInfo(
                    name = "Volume $index",
                    path = "/storage/volume-$index",
                    freeSpace = "$index GB",
                    totalSpace = "128 GB",
                )
            },
        ),
        logLines = emptyList(),
        isLogPaused = false,
        testDataState = TestDataState(
            targetPaths = listOf(
                TargetPathInfo(
                    path = LocalPath.build("/storage/emulated/0"),
                    displayPath = "/storage/emulated/0",
                ),
            ),
            largeFilesEnabled = false,
            nestedStructureEnabled = false,
            textFilesEnabled = true,
            canGenerate = true,
        ),
        optionsState = OptionsState(
            isDebugMode = true,
            isTraceMode = false,
            isFloatingLogEnabled = false,
            rootTestResult = null,
            isRootTesting = false,
            shizukuTestResult = null,
            isShizukuTesting = false,
            canHideDeveloperMode = false,
        ),
    )

    /** The last line the storage section renders for [volumeIndex], and with it the last of the page. */
    private fun lastRowText(volumeIndex: Int) = "$volumeIndex GB free of 128 GB"

    private fun runningOperation(title: String = OPERATION_TITLE) = OperationDisplay(
        id = Operation.Id(),
        startedAt = Clock.System.now(),
        icon = Icons.TwoTone.Delete,
        title = title.toCaString(),
        description = "3 of 10".toCaString(),
        canCancel = true,
        state = OperationDisplay.State.Running(),
    )

    private fun completedOperation(title: String = OPERATION_TITLE) = runningOperation(title).copy(
        state = OperationDisplay.State.Completed(
            summary = "Done".toCaString(),
            completedAt = Clock.System.now(),
            report = null,
        ),
    )

    /**
     * `collapseTargets` holds every registered non-Static bar, and the operations bar is Static
     * while any operation is still active - hence the terminal fixture.
     */
    @Test
    fun `the operations bar registers under the key the developer workspace persists`() {
        operationsSource.value = OperationsDisplayState(operations = listOf(completedOperation()))
        setContent()

        bottomBarKeys() shouldBe setOf(DeveloperBarKeys.OPERATIONS)
    }

    /**
     * Scrolled to its end, the last row of the system section has to sit above the bar. The fixture
     * makes the bar taller than the fixed 80.dp of padding the page used to reserve, which the first
     * assertion pins - a shorter bar would fit inside that reservation and prove nothing.
     */
    @Test
    fun `content scrolls clear of the operations bar`() {
        stateSource.value = systemState(volumeCount = 2)
        operationsSource.value = OperationsDisplayState(
            operations = List(4) { completedOperation(title = "Operation $it") },
        )
        setContent()

        val barTop = composeTestRule
            .onNodeWithText(context.getString(WorkspaceR.string.operations_header_title) + " (4)")
            .getUnclippedBoundsInRoot()
            .top
        val rootBottom = composeTestRule.onRoot().getUnclippedBoundsInRoot().bottom

        withClue("the bar has to be taller than the page's former fixed reservation") {
            (rootBottom - barTop > 80.dp) shouldBe true
        }

        composeTestRule
            .onNode(hasScrollAction() and hasAnyDescendant(hasText(deviceSectionHeader)))
            .performScrollToIndex(LAST_SECTION_INDEX)
        composeTestRule.waitForIdle()

        val lastRow = composeTestRule.onNodeWithText(lastRowText(1)).getUnclippedBoundsInRoot()
        withClue("the last row of the section sits above the bar") {
            (lastRow.bottom <= barTop) shouldBe true
        }
    }

    private val deviceSectionHeader: String
        get() = context.getString(R.string.developer_system_device_header)

    companion object {
        private const val OPERATION_TITLE = "Generating test data"

        /** Device, build, memory, storage - the storage section is last. */
        private const val LAST_SECTION_INDEX = 3
    }
}
