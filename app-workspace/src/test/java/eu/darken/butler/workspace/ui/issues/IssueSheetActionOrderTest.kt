package eu.darken.butler.workspace.ui.issues

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Instant

class IssueSheetActionOrderTest : ComposeTest() {

    private fun createLookup(
        path: String = "/storage/emulated/0/Download/file.txt",
    ) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Instant.fromEpochMilliseconds(0L),
        target = null,
    )

    @Test
    fun `insufficient permission - cancel is left of skip and both resolve`() {
        val resolutions = mutableListOf<PathActionIssue.Resolution>()
        composeTestRule.setContent {
            PreviewWrapper {
                InsufficientPermissionIssueSheet(
                    issue = PathActionIssue.InsufficientPermission(
                        destinationPath = LocalPath.build("/storage/emulated/0/test.txt"),
                        canSkip = true,
                    ),
                    onResolution = { resolutions.add(it) },
                )
            }
        }

        val cancel = composeTestRule.onNodeWithText("Cancel")
        val skip = composeTestRule.onNodeWithText("Skip")
        cancel.assertIsDisplayed()
        skip.assertIsDisplayed()
        cancel.getBoundsInRoot().left shouldBeLessThan skip.getBoundsInRoot().left

        cancel.performClick()
        skip.performClick()
        resolutions[0] shouldBe PathActionIssue.InsufficientPermission.Resolution.Cancel()
        resolutions[1] shouldBe PathActionIssue.InsufficientPermission.Resolution.Skip()
    }

    @Test
    fun `insufficient permission - no skip button when skipping is not possible`() {
        composeTestRule.setContent {
            PreviewWrapper {
                InsufficientPermissionIssueSheet(
                    issue = PathActionIssue.InsufficientPermission(
                        destinationPath = LocalPath.build("/storage/emulated/0/test.txt"),
                        canSkip = false,
                    ),
                    onResolution = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Skip").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `insufficient space - cancel is left of retry and both resolve`() {
        val resolutions = mutableListOf<PathActionIssue.Resolution>()
        composeTestRule.setContent {
            PreviewWrapper {
                InsufficientSpaceIssueSheet(
                    issue = PathActionIssue.InsufficientSpace(
                        source = createLookup(),
                        destinationPath = LocalPath.build("/storage/emulated/0/target"),
                    ),
                    onResolution = { resolutions.add(it) },
                )
            }
        }

        val cancel = composeTestRule.onNodeWithText("Cancel")
        val retry = composeTestRule.onNodeWithText("Retry")
        cancel.getBoundsInRoot().left shouldBeLessThan retry.getBoundsInRoot().left

        cancel.performClick()
        retry.performClick()
        resolutions[0] shouldBe PathActionIssue.InsufficientSpace.Resolution.Cancel()
        resolutions[1] shouldBe PathActionIssue.InsufficientSpace.Resolution.Retry
    }

    @Test
    fun `trash move failed - cancel is left of skip`() {
        composeTestRule.setContent {
            PreviewWrapper {
                TrashMoveFailedIssueSheet(
                    issue = PathActionIssue.TrashMoveFailed(
                        failedItems = listOf(createLookup()),
                    ),
                    onResolution = {},
                )
            }
        }

        val cancel = composeTestRule.onNodeWithText("Cancel")
        val skip = composeTestRule.onNodeWithText("Skip")
        cancel.getBoundsInRoot().left shouldBeLessThan skip.getBoundsInRoot().left
    }

    @Test
    fun `unknown error - skip is left of retry and both resolve`() {
        val resolutions = mutableListOf<PathActionIssue.Resolution>()
        composeTestRule.setContent {
            PreviewWrapper {
                UnknownErrorIssueSheet(
                    issue = PathActionIssue.UnknownError(
                        exception = RuntimeException("test error"),
                        canSkip = true,
                        canRetry = true,
                    ),
                    onResolution = { resolutions.add(it) },
                )
            }
        }

        val skip = composeTestRule.onNodeWithText("Skip")
        val retry = composeTestRule.onNodeWithText("Retry")
        skip.getBoundsInRoot().left shouldBeLessThan retry.getBoundsInRoot().left

        skip.performClick()
        retry.performClick()
        resolutions[0] shouldBe PathActionIssue.UnknownError.Resolution.Skip()
        resolutions[1] shouldBe PathActionIssue.UnknownError.Resolution.Retry
    }

    @Test
    fun `archive password - cancel is left of unlock and both resolve`() {
        val resolutions = mutableListOf<PathActionIssue.Resolution>()
        composeTestRule.setContent {
            PreviewWrapper {
                ArchivePasswordIssueSheet(
                    issue = PathActionIssue.ArchivePasswordRequired(
                        container = LocalPath.build("/storage/emulated/0/Download/secret.zip"),
                    ),
                    onResolution = { resolutions.add(it) },
                )
            }
        }

        val cancel = composeTestRule.onNodeWithText("Cancel")
        val unlock = composeTestRule.onNodeWithText("Unlock")
        cancel.getBoundsInRoot().left shouldBeLessThan unlock.getBoundsInRoot().left

        unlock.assertIsNotEnabled()
        composeTestRule.onNodeWithText("Password").performTextInput("hunter2")
        unlock.performClick()
        cancel.performClick()
        resolutions[0] shouldBe PathActionIssue.ArchivePasswordRequired.Resolution.Submit("hunter2")
        resolutions[1] shouldBe PathActionIssue.ArchivePasswordRequired.Resolution.Cancel()
    }

    @Test
    fun `unknown error - no action row when neither retry nor skip is possible`() {
        composeTestRule.setContent {
            PreviewWrapper {
                UnknownErrorIssueSheet(
                    issue = PathActionIssue.UnknownError(
                        exception = RuntimeException("test error"),
                        canSkip = false,
                        canRetry = false,
                    ),
                    onResolution = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Skip").assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
