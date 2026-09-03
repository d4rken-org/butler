package eu.darken.butler.workspace.ui.dnd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The transfer itself is platform drag and drop and is covered by device QA; what's testable here
 * is that the modifier attaches and that starting a drag without a payload does nothing.
 */
class WorkspaceDragSourceTest : ComposeTest() {

    @Test
    fun `starting a drag without a payload is a no-op`() {
        var source: WorkspaceDragSource? = null
        var payloadRequests = 0

        composeTestRule.setContent {
            PreviewWrapper {
                val dragSource = rememberWorkspaceDragSource {
                    payloadRequests++
                    null
                }
                source = dragSource
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(TAG)
                        .then(dragSource.modifier),
                )
            }
        }

        composeTestRule.onNodeWithTag(TAG).assertIsDisplayed()

        composeTestRule.runOnIdle { source!!.startDrag() }

        composeTestRule.runOnIdle { payloadRequests shouldBe 1 }
    }

    @Test
    fun `starting a drag before the modifier is attached is a no-op`() {
        var payloadRequests = 0
        val source = WorkspaceDragSource {
            payloadRequests++
            null
        }

        source.startDrag()

        payloadRequests shouldBe 0
    }

    @Test
    fun `starting a drag with an empty payload is a no-op`() {
        var decorationRequests = 0
        val source = WorkspaceDragSource(
            decorationProvider = {
                decorationRequests++
                null
            },
        ) {
            WorkspaceDragPayload(
                sourceWorkspaceId = Workspace.Id(),
                items = emptyList(),
                allowMove = true,
            )
        }

        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(TAG)
                        .then(source.modifier),
                )
            }
        }

        composeTestRule.onNodeWithTag(TAG).assertIsDisplayed()

        composeTestRule.runOnIdle { source.startDrag() }

        composeTestRule.runOnIdle { decorationRequests shouldBe 0 }
    }

    companion object {
        private const val TAG = "drag-source"
    }
}
