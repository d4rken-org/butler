package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h1600dp")
class HistoryEntryDetailsBottomSheetTest : ComposeTest() {

    private val completedAt = Instant.parse("2026-09-02T14:31:05Z")

    private fun installEntry(packages: List<HistoryEntry.PackageOutcome>) = HistoryEntry(
        id = "install",
        kind = Operation.Metadata.Kind.INSTALL,
        intent = null,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = "Install app",
        description = "notes-1.4.2.apk",
        summary = null,
        startedAt = completedAt - 6.seconds,
        completedAt = completedAt,
        duration = 6.seconds,
        outcome = HistoryOutcome.COMPLETED,
        errorMessage = null,
        errorClass = null,
        affectedPathsCount = 0,
        partialErrorCount = 0,
        pathsTruncated = false,
        paths = emptyList(),
        packages = packages,
        primaryPath = APK_PATH,
    )

    private fun setSheet(entry: HistoryEntry) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryEntryDetailsBottomSheet(
                        entry = entry,
                        attemptedPaths = listOf(APK_PATH),
                        attemptedPathsTotal = 1,
                        bottomInset = 0.dp,
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun `an install entry shows the installed app and its apk path`() {
        setSheet(
            installEntry(
                listOf(
                    HistoryEntry.PackageOutcome(
                        label = "Notes",
                        packageName = "com.example.notes",
                        status = Operation.Report.Packages.Outcome.Status.DONE,
                        errorMessage = null,
                    ),
                )
            )
        )

        composeTestRule.onNodeWithText(PACKAGES_HEADING).assertExists()
        composeTestRule.onNodeWithText("Notes").assertExists()
        composeTestRule.onNodeWithText("com.example.notes").assertExists()
        composeTestRule.onNodeWithText(ATTEMPTED_PATHS_HEADING).assertExists()
        composeTestRule.onNodeWithText(APK_PATH).assertExists()
    }

    @Test
    fun `an install entry without package rows shows only its apk path`() {
        setSheet(installEntry(emptyList()))

        composeTestRule.onNodeWithText(PACKAGES_HEADING).assertDoesNotExist()
        composeTestRule.onNodeWithText(PACKAGES_EMPTY).assertDoesNotExist()
        composeTestRule.onNodeWithText(APK_PATH).assertExists()
    }

    companion object {
        private const val APK_PATH = "/storage/emulated/0/Download/notes-1.4.2.apk"
        private const val PACKAGES_HEADING = "Affected apps"
        private const val PACKAGES_EMPTY = "No affected apps recorded."
        private const val ATTEMPTED_PATHS_HEADING = "Attempted paths"
    }
}
