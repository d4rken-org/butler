package eu.darken.butler.workspace.ui.manager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceButtonTest : ComposeTest() {

    // Use static mascot to avoid infinite animation loop in Robolectric
    private val testMascotVariant = ButlerMascotMode.Static.Normal()

    private fun quickItem(type: Workspace.Type, title: String) = QuickCreateItem(
        type = type,
        icon = Icons.TwoTone.Add,
        title = title.toCaString(),
        arguments = type.defaultArguments!!,
    )

    private class RecordingButtonProvider(
        state: WorkspaceButtonViewModel.State,
    ) : WorkspaceButtonProvider {
        val created = mutableListOf<QuickCreateItem>()
        val actions = mutableListOf<WorkspaceAction>()
        var templatesCreated = 0
        var managerNavigations = 0
        var settingsNavigations = 0
        override val state: Flow<WorkspaceButtonViewModel.State> = flowOf(state)
        override fun executeWorkspaceAction(action: WorkspaceAction) { actions += action }
        override fun navToWorkspaceManager() { managerNavigations++ }
        override fun navToSettings() { settingsNavigations++ }
        override fun navToUpgradeButler() {}
        override fun createWorkspace(item: QuickCreateItem) { created += item }
        override fun createTemplatesWorkspace() { templatesCreated++ }
    }

    private fun openMenu() =
        composeTestRule.onNodeWithTag(WorkspaceButtonDefaults.TEST_TAG).performClick()

    @Test
    fun `displays workspace count badge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 5,
                            operationsCount = 0,
                            attentionCount = 0,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        composeTestRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun `displays operations count badge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 1,
                            operationsCount = 3,
                            attentionCount = 0,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `displays attention count badge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 1,
                            operationsCount = 0,
                            attentionCount = 2,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `workspace count over 9 shows 9+`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 12,
                            operationsCount = 0,
                            attentionCount = 0,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        composeTestRule.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun `operations count over 9 shows 9+`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 1,
                            operationsCount = 15,
                            attentionCount = 0,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        composeTestRule.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun `attention count over 9 shows 9+`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 1,
                            operationsCount = 0,
                            attentionCount = 10,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        composeTestRule.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun `all badges display with correct counts`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 5,
                            operationsCount = 7,
                            attentionCount = 3,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `zero counts hide badges`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
                        WorkspaceButtonViewModel.State(
                            workspaceCount = 0,
                            operationsCount = 0,
                            attentionCount = 0,
                        )
                    )
                ) {
                    WorkspaceButton(mascotVariant = testMascotVariant)
                }
            }
        }

        // Only "9+" badge text patterns should not exist
        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }

    private fun setContent(
        provider: RecordingButtonProvider,
        currentWorkspaceId: Workspace.Id? = null,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceButtonProvider provides provider) {
                    WorkspaceButton(
                        mascotVariant = testMascotVariant,
                        currentWorkspaceId = currentWorkspaceId,
                    )
                }
            }
        }
    }

    @Test
    fun `recent rows and the new tab row are shown alongside existing items`() {
        val provider = RecordingButtonProvider(
            WorkspaceButtonViewModel.State(
                recentItems = listOf(
                    quickItem(Workspace.Type.EXPLORER, "Explorer"),
                    quickItem(Workspace.Type.SEARCHER, "Searcher"),
                ),
            )
        )
        setContent(provider)

        openMenu()

        composeTestRule.onNodeWithText("New Explorer").assertIsDisplayed()
        composeTestRule.onNodeWithText("New Searcher").assertIsDisplayed()
        composeTestRule.onNodeWithText("New tab").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tab manager").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `category headers group the menu`() {
        val provider = RecordingButtonProvider(
            WorkspaceButtonViewModel.State(
                recentItems = listOf(quickItem(Workspace.Type.EXPLORER, "Explorer")),
            )
        )
        setContent(provider, currentWorkspaceId = Workspace.Id())

        openMenu()

        composeTestRule.onNodeWithText("Recently").assertIsDisplayed()
        composeTestRule.onNodeWithText("Other").assertIsDisplayed()
        composeTestRule.onNodeWithText("This tab").assertIsDisplayed()
    }

    @Test
    fun `the recently header is hidden while there are no recent items`() {
        val provider = RecordingButtonProvider(WorkspaceButtonViewModel.State())
        setContent(provider, currentWorkspaceId = Workspace.Id())

        openMenu()

        composeTestRule.onNodeWithText("Recently").assertDoesNotExist()
        composeTestRule.onNodeWithText("Other").assertIsDisplayed()
    }

    @Test
    fun `clicking a recent row creates that workspace`() {
        val explorer = quickItem(Workspace.Type.EXPLORER, "Explorer")
        val provider = RecordingButtonProvider(
            WorkspaceButtonViewModel.State(recentItems = listOf(explorer))
        )
        setContent(provider)

        openMenu()
        composeTestRule.onNodeWithText("New Explorer").performClick()

        provider.created.map { it.type } shouldBe listOf(Workspace.Type.EXPLORER)
    }

    @Test
    fun `clicking new tab creates the templates picker`() {
        val provider = RecordingButtonProvider(
            WorkspaceButtonViewModel.State(
                recentItems = listOf(quickItem(Workspace.Type.EXPLORER, "Explorer")),
            )
        )
        setContent(provider)

        openMenu()
        composeTestRule.onNodeWithText("New tab").performClick()

        provider.templatesCreated shouldBe 1
    }

    @Test
    fun `tab manager and settings rows still navigate`() {
        val provider = RecordingButtonProvider(WorkspaceButtonViewModel.State())
        setContent(provider)

        openMenu()
        composeTestRule.onNodeWithText("Tab manager").performClick()

        openMenu()
        composeTestRule.onNodeWithText("Settings").performClick()

        provider.managerNavigations shouldBe 1
        provider.settingsNavigations shouldBe 1
    }

    @Test
    fun `close current is hidden when there is no current workspace`() {
        val provider = RecordingButtonProvider(WorkspaceButtonViewModel.State())
        setContent(provider, currentWorkspaceId = null)

        openMenu()

        composeTestRule.onNodeWithText("This tab").assertDoesNotExist()
        composeTestRule.onNodeWithText("Close current tab").assertDoesNotExist()
    }

    /** No unit entry means the snapshot never saw this id; closing it alone still beats doing nothing. */
    @Test
    fun `close current falls back to the current workspace when its unit is unknown`() {
        val currentId = Workspace.Id()
        val provider = RecordingButtonProvider(WorkspaceButtonViewModel.State(workspaceCount = 2))
        setContent(provider, currentWorkspaceId = currentId)

        openMenu()
        composeTestRule.onNodeWithText("Close current tab").performClick()

        provider.actions shouldBe listOf(
            WorkspaceAction.Close(id = currentId, sourceWorkspaceId = currentId, undoable = true)
        )
    }

    @Test
    fun `close current closes the whole stack, not the overlay it was opened from`() {
        val ownerId = Workspace.Id()
        val overlayId = Workspace.Id()
        val provider = RecordingButtonProvider(
            WorkspaceButtonViewModel.State(
                workspaceCount = 2,
                unitsByMember = mapOf(
                    overlayId to WorkspaceButtonViewModel.StackUnit(ownerId = ownerId, size = 2),
                ),
            )
        )
        setContent(provider, currentWorkspaceId = overlayId)

        openMenu()
        composeTestRule.onNodeWithText("Close current tab (2 workspaces)").performClick()

        // sourceWorkspaceId stays the overlay: a close confirmation is hosted in its target's pane
        // layer, so anchoring it to the owner would hide it under the overlay that asked for it.
        provider.actions shouldBe listOf(
            WorkspaceAction.Close(id = ownerId, sourceWorkspaceId = overlayId, undoable = true)
        )
    }

    @Test
    fun `a tab with nothing stacked on it keeps the plain close label`() {
        val ownerId = Workspace.Id()
        val provider = RecordingButtonProvider(
            WorkspaceButtonViewModel.State(
                workspaceCount = 1,
                unitsByMember = mapOf(
                    ownerId to WorkspaceButtonViewModel.StackUnit(ownerId = ownerId, size = 1),
                ),
            )
        )
        setContent(provider, currentWorkspaceId = ownerId)

        openMenu()

        composeTestRule.onNodeWithText("Close current tab").assertIsDisplayed()
        composeTestRule.onNodeWithText("Close current tab (1 workspace)").assertDoesNotExist()
    }

    @Test
    fun `long-pressing close current confirms closing all workspaces`() {
        val provider = RecordingButtonProvider(WorkspaceButtonViewModel.State(workspaceCount = 3))
        setContent(provider, currentWorkspaceId = Workspace.Id())

        openMenu()
        composeTestRule.onNodeWithText("Close current tab").performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Close all tabs?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Close all").performClick()

        provider.actions shouldBe listOf(WorkspaceAction.CloseAll)
    }
}
