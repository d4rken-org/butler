package eu.darken.butler.apps.ui.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.details.PackageInfoState
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.apps.ui.details.components.ComponentsActionBarItem
import eu.darken.butler.apps.ui.details.components.previewComponentsData
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class AppDetailsWorkspacePageTest : ComposeTest() {

    /** App Details stacked on its caller, which is how it opens on a phone. */
    private val stackedOnCaller = Workspace.Id()

    /**
     * What keeps the toolbar's workspace button out of these fixtures: its animated mascot never
     * goes idle under Robolectric, so any assertion that waits for idle would hang. Being stacked
     * no longer suppresses the button - only a multi-pane layout does, because there the navigation
     * rail carries it instead.
     */
    private val multiPane = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL)

    @Test
    fun `components route shows components title and back returns to overview`() {
        val actions = mutableListOf<AppDetailsPageAction>()
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = multiPane,
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.COMPONENTS,
                        callerWorkspaceId = stackedOnCaller,
                    ),
                    workspaceId = Workspace.Id(),
                    onPageAction = { actions += it },
                )
            }
        }

        composeTestRule.onNodeWithText("Components").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        actions shouldBe listOf(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW))
    }

    private fun setPackageInfoPage(
        packageInfo: PackageInfoState,
        actions: MutableList<AppDetailsPageAction> = mutableListOf(),
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = multiPane,
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.PACKAGE_INFO,
                        packageInfo = packageInfo,
                        callerWorkspaceId = stackedOnCaller,
                    ),
                    workspaceId = Workspace.Id(),
                    onPageAction = { actions += it },
                )
            }
        }
    }

    @Test
    fun `package info route shows the manifest data and back returns to overview`() {
        val actions = mutableListOf<AppDetailsPageAction>()
        setPackageInfoPage(PackageInfoState.Ready(previewPackageInfo), actions)

        composeTestRule.onNodeWithText("121.0.6167.101 (616710103)").assertExists()
        composeTestRule.onNodeWithText("android.permission.CAMERA").assertExists()
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        actions shouldBe listOf(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW))
    }

    /**
     * autoAdvance off: the progress indicator animates forever, and waiting for idle with it on
     * screen would never return under Robolectric.
     */
    @Test
    fun `package info renders a spinner while it loads`() {
        composeTestRule.mainClock.autoAdvance = false
        setPackageInfoPage(PackageInfoState.Loading)

        composeTestRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assertExists()
        composeTestRule.onNodeWithText("The app's package details could not be read.").assertDoesNotExist()
    }

    @Test
    fun `package info explains itself when nothing could be read`() {
        setPackageInfoPage(PackageInfoState.Unavailable)

        composeTestRule
            .onNodeWithText("The app's package details could not be read.")
            .assertIsDisplayed()
    }

    @Test
    fun `the overview offers a route into the package info screen`() {
        val actions = mutableListOf<AppDetailsPageAction>()
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = multiPane,
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.OVERVIEW,
                        callerWorkspaceId = stackedOnCaller,
                    ),
                    workspaceId = Workspace.Id(),
                    onPageAction = { actions += it },
                )
            }
        }

        composeTestRule.onNodeWithText("View package details").performClick()

        actions shouldBe listOf(AppDetailsPageAction.NavigateToTab(DetailTab.PACKAGE_INFO))
    }

    @Test
    fun `overview shows package name once - not duplicated in the toolbar`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = multiPane,
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.OVERVIEW,
                        // Modal so the toolbar renders a back button.
                        callerWorkspaceId = Workspace.Id(),
                    ),
                    workspaceId = Workspace.Id(),
                    onPageAction = {},
                )
            }
        }

        // App name is in the toolbar; the package name appears only in the overview card (exactly one).
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("com.android.chrome").assertIsDisplayed()
    }

    private fun setComponentsPage(
        selectedComponentKeys: Set<String>,
        componentActions: List<ComponentsActionBarItem>,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = multiPane,
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.COMPONENTS,
                        callerWorkspaceId = stackedOnCaller,
                    ),
                    componentsState = ComponentsUiState.Ready(previewComponentsData),
                    selectedComponentKeys = selectedComponentKeys,
                    componentActions = componentActions,
                    workspaceId = Workspace.Id(),
                    onPageAction = {},
                )
            }
        }
    }

    @Test
    fun `a selection with available actions shows the info bar and the action bar`() {
        val selected = previewComponentsData.activities
        setComponentsPage(
            selectedComponentKeys = selected.map { it.key }.toSet(),
            componentActions = listOf(ComponentsActionBarItem.Disable(selected)),
        )

        composeTestRule.onNodeWithText("2 selected").assertExists()
        composeTestRule.onNodeWithContentDescription("Disable").assertExists()
    }

    /** Without elevated access the ViewModel hands the page no actions, so only the info bar shows. */
    @Test
    fun `a selection without actions still shows the info bar`() {
        val selected = previewComponentsData.activities
        setComponentsPage(
            selectedComponentKeys = selected.map { it.key }.toSet(),
            componentActions = emptyList(),
        )

        composeTestRule.onNodeWithText("2 selected").assertExists()
        composeTestRule.onNodeWithContentDescription("Disable").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Enable").assertDoesNotExist()
    }

    /**
     * The selection lives on the ViewModel and deliberately survives a pane remount, so the page
     * must not clear it just for entering composition. `key(…)` is the closest thing to a real
     * remount the Robolectric harness offers: flipping it disposes the page's subtree — including
     * its effects and its page-local search state — and composes a fresh one, exactly what a
     * rotation or pane-layout change does to this slot.
     */
    @Test
    fun `a selection survives a remount with an unchanged query`() {
        val actions = mutableListOf<AppDetailsPageAction>()
        var remountKey by mutableStateOf(0)
        composeTestRule.setContent {
            PreviewWrapper {
                key(remountKey) {
                    AppDetailsWorkspacePage(
                        design = multiPane,
                        state = AppDetailsWorkspace.State(
                            app = AppsMockDataProvider.Presets.chrome,
                            selectedTab = DetailTab.COMPONENTS,
                            callerWorkspaceId = stackedOnCaller,
                        ),
                        componentsState = ComponentsUiState.Ready(previewComponentsData),
                        selectedComponentKeys = previewComponentsData.activities.map { it.key }.toSet(),
                        workspaceId = Workspace.Id(),
                        onPageAction = { actions += it },
                    )
                }
            }
        }

        // Not even the first composition may clear: the page can mount onto an existing selection.
        actions shouldBe emptyList()

        composeTestRule.runOnIdle { remountKey = 1 }
        composeTestRule.waitForIdle()

        actions shouldBe emptyList()
    }

    /**
     * Pins the real entry order: the route is entered with nothing selected, the user long-presses
     * to select, and only then searches. The effect that watches the query launches once and keeps
     * its original lambda, so anything it reads from the page's parameters is frozen at the empty
     * first composition — gating the clear on the selection would silently never fire again.
     */
    @Test
    fun `editing the search query clears a selection made after the page composed`() {
        val actions = mutableListOf<AppDetailsPageAction>()
        var selectedKeys by mutableStateOf(emptySet<String>())
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = multiPane,
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.COMPONENTS,
                        callerWorkspaceId = stackedOnCaller,
                    ),
                    componentsState = ComponentsUiState.Ready(previewComponentsData),
                    selectedComponentKeys = selectedKeys,
                    workspaceId = Workspace.Id(),
                    onPageAction = { actions += it },
                )
            }
        }

        composeTestRule.runOnIdle {
            selectedKeys = previewComponentsData.activities.map { it.key }.toSet()
        }
        composeTestRule.waitForIdle()
        actions shouldBe emptyList()

        composeTestRule.onNodeWithContentDescription("Search components").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Main")
        composeTestRule.waitForIdle()

        actions shouldBe listOf(AppDetailsPageAction.ClearComponentSelection)
    }
}
