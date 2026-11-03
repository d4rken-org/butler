package eu.darken.butler.workspace.ui.issues

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.R
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant

@Config(application = TestApplication::class, sdk = [34])
class PathAlreadyExistsIssueSheetLabelTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun lookup(path: String, fileType: FileType) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = fileType,
        size = 1024L,
        modifiedAt = Instant.fromEpochMilliseconds(0L),
        target = null,
    )

    private fun setSheet(source: LocalPathLookup, destination: LocalPathLookup) {
        composeTestRule.setContent {
            PreviewWrapper {
                PathAlreadyExistsIssueSheet(
                    issue = PathActionIssue.PathAlreadyExists(
                        source = source,
                        destination = destination,
                        canSkip = true,
                        canRenameSource = true,
                    ),
                    onResolution = {},
                )
            }
        }
    }

    @Test
    fun `a file renamed onto a folder is labelled as an incoming file`() {
        setSheet(
            source = lookup("/storage/emulated/0/Download/report.txt", FileType.FILE),
            destination = lookup("/storage/emulated/0/Download/Photos", FileType.DIRECTORY),
        )

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_issue_collision_existing_folder_label))
            .assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.workspace_issue_collision_new_file))
            .assertExists()
        composeTestRule.onAllNodesWithText(context.getString(R.string.workspace_issue_collision_new_folder))
            .assertCountEquals(0)
    }

    @Test
    fun `a folder onto a folder is labelled as an incoming folder`() {
        setSheet(
            source = lookup("/storage/emulated/0/Desktop/Photos", FileType.DIRECTORY),
            destination = lookup("/storage/emulated/0/Download/Photos", FileType.DIRECTORY),
        )

        composeTestRule.onNodeWithText(context.getString(R.string.workspace_issue_collision_existing_folder_label))
            .assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.workspace_issue_collision_new_folder))
            .assertExists()
        composeTestRule.onAllNodesWithText(context.getString(R.string.workspace_issue_collision_new_file))
            .assertCountEquals(0)
    }
}
