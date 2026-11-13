package eu.darken.butler.explorer.ui.explorer.elements

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Home
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.common.WorkspaceToolbarDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR

@Config(qualifiers = "w400dp-h800dp")
class ExplorerToolbarCardTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cardTag = "card"

    private val breadcrumbs = listOf(
        ExplorerBreadcrumb(
            label = "Home".toCaString(),
            icon = Icons.TwoTone.Home,
            target = ExplorerNavigation.Target.Home,
        ),
        ExplorerBreadcrumb(
            label = "Download".toCaString(),
            icon = Icons.TwoTone.FolderOpen,
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Download")),
        ),
        ExplorerBreadcrumb(
            label = LAST_SEGMENT.toCaString(),
            icon = Icons.TwoTone.FolderOpen,
            target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Download/$LAST_SEGMENT")),
        ),
    )
    private val collapsedLabel = listOf("Home", "Download", LAST_SEGMENT).joinToString("\u2009/\u2009")

    private val cancelLabel: String
        get() = context.getString(CommonR.string.general_cancel_action)
    private val selectLabel: String
        get() = context.getString(R.string.explorer_picker_select_this_folder_action)

    private var expandCalls = 0
    private var cancelCalls = 0
    private var confirmCalls = 0

    private fun setCard(
        collapsedFraction: Float = 1f,
        pickerSelection: PickerConfig.Selection? = null,
        canConfirmSelection: Boolean = true,
        withExpand: Boolean = true,
        cardModifier: Modifier = Modifier,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = cardModifier) {
                    ExplorerToolbarCard(
                        modifier = Modifier.testTag(cardTag),
                        workspaceId = Workspace.Id(),
                        breadcrumbs = breadcrumbs,
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the toolbar cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        collapsedFraction = collapsedFraction,
                        onExpand = if (withExpand) {
                            { expandCalls++ }
                        } else {
                            null
                        },
                        onBreadcrumbClick = {},
                        onNavigateToPath = {},
                        pickerSelection = pickerSelection,
                        canConfirmSelection = canConfirmSelection,
                        onCancel = { cancelCalls++ },
                        onConfirm = { confirmCalls++ },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun tapCentreOfText(text: String) {
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).performTouchInput { click() }
        composeTestRule.waitForIdle()
    }

    // Real font metrics: the default graphics mode measures every line of text at 36dp (see
    // ComposeTest), which is taller than the height floor this case is about.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `the tappable collapsed toolbar keeps the collapsed toolbar height`() {
        setCard()

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    @Test
    fun `tapping the collapsed path asks the bar to expand`() {
        setCard()

        tapCentreOfText(collapsedLabel)

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the collapsed folder icon asks the bar to expand`() {
        setCard()

        composeTestRule.onNode(hasClickAction() and hasText(collapsedLabel)).performTouchInput {
            // The icon sits after the row's 8dp start padding and is 20dp wide.
            click(Offset(18.dp.toPx(), centerY))
        }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the expanded breadcrumbs does not ask the bar to expand`() {
        setCard(collapsedFraction = 0f)

        tapCentreOfText(LAST_SEGMENT)

        expandCalls shouldBe 0
    }

    @Test
    fun `the collapsed path is not tappable without an expand handler`() {
        setCard(withExpand = false)

        composeTestRule.onNodeWithText(collapsedLabel).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction() and hasText(collapsedLabel)).assertCountEquals(0)
    }

    private fun assertPickerTargetsStaySeparate() {
        tapCentreOfText(collapsedLabel)
        expandCalls shouldBe 1

        composeTestRule.onNodeWithText(cancelLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()
        cancelCalls shouldBe 1
        expandCalls shouldBe 1

        composeTestRule.onNodeWithText(selectLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()
        confirmCalls shouldBe 1
        expandCalls shouldBe 1
    }

    @Test
    fun `the collapsed picker path expands while its buttons keep their own actions`() {
        setCard(pickerSelection = PickerConfig.Selection.DirectorySingle)

        assertPickerTargetsStaySeparate()
    }

    @Test
    fun `the picker button edges facing the path keep their own actions`() {
        setCard(pickerSelection = PickerConfig.Selection.DirectorySingle)

        composeTestRule.onNodeWithText(cancelLabel).performTouchInput { click(Offset(width - 2f, centerY)) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(selectLabel).performTouchInput { click(Offset(1f, centerY)) }
        composeTestRule.waitForIdle()

        cancelCalls shouldBe 1
        confirmCalls shouldBe 1
        expandCalls shouldBe 0
    }

    @Test
    fun `a narrow collapsed picker keeps the path and its buttons separate`() {
        setCard(
            pickerSelection = PickerConfig.Selection.DirectorySingle,
            cardModifier = Modifier.width(280.dp),
        )

        assertPickerTargetsStaySeparate()
    }

    @Test
    fun `tapping the disabled select button neither confirms nor expands`() {
        setCard(
            pickerSelection = PickerConfig.Selection.DirectorySingle,
            canConfirmSelection = false,
        )

        composeTestRule.onNodeWithText(selectLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()

        confirmCalls shouldBe 0
        expandCalls shouldBe 0
    }

    companion object {
        private const val LAST_SEGMENT = "Photos"
    }
}
