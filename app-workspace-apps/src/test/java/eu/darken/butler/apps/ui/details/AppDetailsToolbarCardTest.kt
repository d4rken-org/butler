package eu.darken.butler.apps.ui.details

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.R
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.common.WorkspaceToolbarDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

// Real font metrics: the default graphics mode measures every line of text at 36dp (see
// ComposeTest), which is taller than the height floor the collapsed case is about.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppDetailsToolbarCardTest : ComposeTest() {

    // Every case renders multi-pane on purpose: that is the branch without a workspace button, and
    // the switcher's animated mascot never idles under Robolectric.
    private val multiPane = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL)

    private val cardTag = "card"

    @Test
    fun `toolbar shows app name but not package name`() {
        composeTestRule.setContent {
            PreviewWrapper {
                // An overview: no sub-screen to go back from.
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                )
            }
        }

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("com.android.chrome").assertDoesNotExist()
    }

    @Test
    fun `toolbar stacks app name over subtitle`() {
        composeTestRule.setContent {
            PreviewWrapper {
                // The Components sub-screen: its back button returns to the overview.
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Components").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome · Components").assertDoesNotExist()
    }

    @Test
    fun `toolbar without an app name keeps the subtitle on one line`() {
        composeTestRule.setContent {
            PreviewWrapper {
                // Nothing to stack the subtitle under before the app resolves.
                AppDetailsToolbarCard(
                    app = null,
                    design = multiPane,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Components").assertIsDisplayed()
    }

    @Test
    fun `collapsed toolbar joins app name and subtitle into one line`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                    collapsedFraction = 1f,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        // Collapsed has room for one line only, so the two are joined there.
        composeTestRule.onNodeWithText("Chrome · Components").assertIsDisplayed()
    }

    @Test
    fun `an open search field hides the back button`() {
        composeTestRule.setContent {
            var searchActive by remember { mutableStateOf(false) }
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                    subtitle = "Components",
                    onBackClick = {},
                    backContentDescription = "Back to app details",
                    searchActive = searchActive,
                    searchQuery = TextFieldValue(),
                    onSearchToggle = { searchActive = !searchActive },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Back to app details").assertIsDisplayed()

        // The back arrow and the app icon step aside to make room for the query; closing the search
        // is what brings them back.
        composeTestRule.onNodeWithContentDescription("Search components").performClick()
        composeTestRule.onNodeWithContentDescription("Back to app details").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.onNodeWithContentDescription("Back to app details").assertIsDisplayed()
    }

    @Test
    fun `toggling search replaces the title with the input`() {
        composeTestRule.setContent {
            var searchActive by remember { mutableStateOf(false) }
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                    subtitle = "Components",
                    onBackClick = {},
                    searchActive = searchActive,
                    searchQuery = TextFieldValue(),
                    onSearchToggle = { searchActive = !searchActive },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search components").performClick()

        // The icon's description and the field's hint are deliberately different strings, so the
        // open and closed states are distinguishable from the semantics tree alone.
        composeTestRule.onNodeWithText("Search name or package").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun `the collapsed toolbar is as tall as the other workspaces' toolbars`() {
        composeTestRule.setContent {
            PreviewWrapper {
                // The overview: without the back and search controls nothing inside the card
                // reaches the floor on its own, so the height comes from the floor.
                AppDetailsToolbarCard(
                    modifier = Modifier.testTag(cardTag),
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                    collapsedFraction = 1f,
                    onBackClick = null,
                    searchActive = false,
                    onSearchToggle = null,
                )
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    @Test
    fun `no search affordance without a toggle handler`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search components").assertDoesNotExist()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val app = AppsMockDataProvider.Presets.chrome
    private val appLabel: String
        get() = app.label.get(context)
    private val subtitle: String
        get() = context.getString(R.string.apps_details_section_components)
    private val collapsedTitle: String
        get() = "$appLabel · $subtitle"
    private val backLabel: String
        get() = context.getString(R.string.appdetails_back_generic_action)
    private val searchToggleLabel: String
        get() = context.getString(R.string.apps_components_search_action)

    private var expandCalls = 0
    private var backCalls = 0
    private var searchToggleCalls = 0

    private fun setCard(
        collapsedFraction: Float = 1f,
        searchActive: Boolean = false,
        withExpand: Boolean = true,
        cardModifier: Modifier = Modifier,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = cardModifier) {
                    // The Components sub-screen: back button, title and search toggle side by side.
                    AppDetailsToolbarCard(
                        app = app,
                        design = multiPane,
                        collapsedFraction = collapsedFraction,
                        subtitle = subtitle,
                        onBackClick = { backCalls++ },
                        searchActive = searchActive,
                        searchQuery = TextFieldValue(),
                        onSearchToggle = { searchToggleCalls++ },
                        onExpand = if (withExpand) {
                            { expandCalls++ }
                        } else {
                            null
                        },
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

    @Test
    fun `the tappable collapsed toolbar keeps the collapsed toolbar height`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    modifier = Modifier.testTag(cardTag),
                    app = app,
                    design = multiPane,
                    collapsedFraction = 1f,
                    onExpand = { expandCalls++ },
                )
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    @Test
    fun `tapping the collapsed title asks the bar to expand`() {
        setCard()

        tapCentreOfText(collapsedTitle)

        expandCalls shouldBe 1
        backCalls shouldBe 0
        searchToggleCalls shouldBe 0
    }

    @Test
    fun `tapping the collapsed app icon asks the bar to expand`() {
        setCard()

        composeTestRule.onNodeWithContentDescription(appLabel, useUnmergedTree = true)
            .performTouchInput { click() }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the expanded title does not ask the bar to expand`() {
        setCard(collapsedFraction = 0f)

        tapCentreOfText(appLabel)

        expandCalls shouldBe 0
    }

    @Test
    fun `the collapsed title is not tappable without an expand handler`() {
        setCard(withExpand = false)

        composeTestRule.onNodeWithText(collapsedTitle).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction() and hasText(collapsedTitle)).assertCountEquals(0)
    }

    private fun assertTitleAndControlsStaySeparate() {
        tapCentreOfText(collapsedTitle)
        expandCalls shouldBe 1

        composeTestRule.onNodeWithContentDescription(backLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()
        backCalls shouldBe 1

        composeTestRule.onNodeWithContentDescription(searchToggleLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()
        searchToggleCalls shouldBe 1

        expandCalls shouldBe 1
    }

    @Test
    fun `the collapsed title expands while back and search keep their own actions`() {
        setCard()

        assertTitleAndControlsStaySeparate()
    }

    @Test
    fun `the back button edge facing the title keeps its own action`() {
        setCard()

        composeTestRule.onNodeWithContentDescription(backLabel).performTouchInput {
            click(Offset(width - 1f, centerY))
        }
        composeTestRule.waitForIdle()

        backCalls shouldBe 1
        expandCalls shouldBe 0
    }

    @Test
    fun `the search toggle edge facing the title keeps its own action`() {
        setCard()

        composeTestRule.onNodeWithContentDescription(searchToggleLabel).performTouchInput {
            click(Offset(1f, centerY))
        }
        composeTestRule.waitForIdle()

        searchToggleCalls shouldBe 1
        expandCalls shouldBe 0
    }

    @Test
    fun `a narrow collapsed toolbar keeps the title and the controls separate`() {
        setCard(cardModifier = Modifier.width(280.dp))

        assertTitleAndControlsStaySeparate()
    }

    @Test
    fun `the collapsed search field is never inside the expand target`() {
        setCard(searchActive = true)

        composeTestRule
            .onAllNodes(hasClickAction() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true)
            .assertCountEquals(0)

        composeTestRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 0
    }
}
