package eu.darken.butler.apps.ui.details.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Content assertions only — the sheet scrolls and Robolectric's text measurement is nominal, so
 * these check what the sheet renders, never where.
 */
class ComponentDetailsSheetTest : ComposeTest() {

    private val exportedActivity = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.MainActivity",
        isExported = true,
        enabledState = ComponentEnabledState.ENABLED,
    )

    private fun setSheet(
        entry: ComponentEntry,
        onLaunch: (() -> Unit)? = null,
        toggleState: ComponentToggleState = ComponentToggleState.UNSUPPORTED,
        onSetEnabled: (Boolean) -> Unit = {},
        onSetupRequested: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    ComponentDetailsSheet(
                        entry = entry,
                        onDismiss = {},
                        onLaunch = onLaunch,
                        toggleState = toggleState,
                        onSetEnabled = onSetEnabled,
                        onSetupRequested = onSetupRequested,
                    )
                }
            }
        }
    }

    @Test
    fun `the hero card shows the short and the fully-qualified name`() {
        setSheet(exportedActivity)

        composeTestRule.onNodeWithText("MainActivity").assertExists()
        composeTestRule.onNodeWithText("com.example.app.MainActivity").assertExists()
    }

    @Test
    fun `launch is offered for an exported enabled activity`() {
        setSheet(exportedActivity, onLaunch = {})

        composeTestRule.onNodeWithText("Launch").assertExists()
    }

    @Test
    fun `launch is absent without a launch handler`() {
        setSheet(exportedActivity)

        composeTestRule.onNodeWithText("Launch").assertDoesNotExist()
    }

    @Test
    fun `the copy actions are always offered`() {
        setSheet(exportedActivity)

        composeTestRule.onNodeWithText("Copy component name").assertExists()
        composeTestRule.onNodeWithText("Copy package name").assertExists()
    }

    @Test
    fun `the state row is omitted while the state is unresolved`() {
        setSheet(exportedActivity.copy(enabledState = ComponentEnabledState.UNRESOLVED))

        composeTestRule.onNodeWithText("STATE").assertDoesNotExist()
        composeTestRule.onNodeWithText("Enabled").assertDoesNotExist()
    }

    @Test
    fun `the state row appears once the state is resolved`() {
        setSheet(exportedActivity)

        composeTestRule.onNodeWithText("STATE").assertExists()
        composeTestRule.onNodeWithText("Enabled").assertExists()
    }

    @Test
    fun `a disabled component reads disabled`() {
        setSheet(exportedActivity.copy(enabledState = ComponentEnabledState.DISABLED))

        composeTestRule.onNodeWithText("STATE").assertExists()
        // Twice: the hero chip and the state row.
        composeTestRule.onAllNodesWithText("Disabled").assertCountEquals(2)
    }

    @Test
    fun `an activity has no authority row`() {
        setSheet(exportedActivity)

        composeTestRule.onNodeWithText("AUTHORITY").assertDoesNotExist()
    }

    @Test
    fun `the toggle row is absent while toggling is unsupported`() {
        setSheet(exportedActivity, toggleState = ComponentToggleState.UNSUPPORTED)

        composeTestRule.onNodeWithText("Disable component").assertDoesNotExist()
        composeTestRule.onNodeWithText("Enable component").assertDoesNotExist()
    }

    @Test
    fun `the toggle row is absent while the state is unresolved`() {
        setSheet(
            exportedActivity.copy(enabledState = ComponentEnabledState.UNRESOLVED),
            toggleState = ComponentToggleState.AVAILABLE,
        )

        composeTestRule.onNodeWithText("Disable component").assertDoesNotExist()
        composeTestRule.onNodeWithText("Enable component").assertDoesNotExist()
    }

    @Test
    fun `an enabled component offers to disable it`() {
        setSheet(exportedActivity, toggleState = ComponentToggleState.AVAILABLE)

        composeTestRule.onNodeWithText("Disable component").assertExists()
        composeTestRule.onNodeWithText("Enable component").assertDoesNotExist()
    }

    @Test
    fun `a disabled component offers to enable it`() {
        setSheet(
            exportedActivity.copy(enabledState = ComponentEnabledState.DISABLED),
            toggleState = ComponentToggleState.AVAILABLE,
        )

        composeTestRule.onNodeWithText("Enable component").assertExists()
        composeTestRule.onNodeWithText("Disable component").assertDoesNotExist()
    }

    @Test
    fun `tapping the toggle inverts the current state`() {
        val values = mutableListOf<Boolean>()
        setSheet(
            exportedActivity,
            toggleState = ComponentToggleState.AVAILABLE,
            onSetEnabled = { values += it },
        )

        composeTestRule.onNodeWithText("Disable component").performScrollTo().performClick()

        values shouldBe listOf(false)
    }

    @Test
    fun `without elevated access the toggle explains itself and routes to setup`() {
        var setupRequests = 0
        val values = mutableListOf<Boolean>()
        setSheet(
            exportedActivity,
            toggleState = ComponentToggleState.NEEDS_SETUP,
            onSetEnabled = { values += it },
            onSetupRequested = { setupRequests++ },
        )

        composeTestRule.onNodeWithText("Requires root or Shizuku — tap to set up").assertExists()
        composeTestRule.onNodeWithText("Disable component").performScrollTo().performClick()

        setupRequests shouldBe 1
        values shouldBe emptyList()
    }

    @Test
    fun `a provider shows its authority`() {
        setSheet(
            ComponentEntry(
                kind = ComponentKind.PROVIDER,
                packageName = "com.example.app",
                className = "com.example.app.data.FileProvider",
                isExported = false,
                enabledState = ComponentEnabledState.ENABLED,
                authority = "com.example.app.files",
            )
        )

        composeTestRule.onNodeWithText("AUTHORITY").assertExists()
        composeTestRule.onNodeWithText("com.example.app.files").assertExists()
    }
}
