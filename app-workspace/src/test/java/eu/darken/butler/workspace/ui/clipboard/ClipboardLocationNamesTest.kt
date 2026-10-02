package eu.darken.butler.workspace.ui.clipboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.network.NetworkLocationNames
import eu.darken.butler.common.files.sftp.SftpPathLookup
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardEntryRow
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A clip keeps its paths while the location behind them is renamed in another pane. */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp")
class ClipboardLocationNamesTest : ComposeTest() {

    private val locationId = Uuid.parse("c0360031-0000-4000-8000-000000000002")

    private val location = SftpLocation(
        id = locationId,
        label = null,
        host = "cnc-dev",
        username = "darken",
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = TrustedHostKey(
            type = "ssh-ed25519",
            blob = ByteArray(51),
            fingerprint = "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM",
        ),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private val clip = ClipboardClip.Paths(
        origin = Workspace.Id(),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            SftpPathLookup(
                lookedUp = SftpPath(locationId, listOf("docs", "a.txt")),
                fileType = FileType.FILE,
                size = null,
                modifiedAt = null,
            ),
        ),
    )

    @After
    fun resetNames() = NetworkLocationNames.clear()

    private fun exists(text: String) = composeTestRule
        .onAllNodes(hasText(text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun mentions(text: String) = composeTestRule
        .onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun rename(label: String) {
        NetworkLocationNames.updateSftp(listOf(location.copy(label = label)))
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the info sheet's header and source follow a rename while the item list is collapsed`() {
        NetworkLocationNames.updateSftp(listOf(location))
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(
                    modifier = Modifier.fillMaxSize(),
                    paneFocused = true,
                ) {
                    ClipboardInfoBottomSheet(clip = clip, onDismiss = {})
                }
            }
        }
        composeTestRule
            .onAllNodes(hasContentDescription("Expand"), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty() shouldBe true
        exists("sftp://cnc-dev/docs/a.txt") shouldBe true
        exists("sftp://cnc-dev/docs") shouldBe true

        rename("Build box")

        exists("sftp://Build box/docs/a.txt") shouldBe true
        exists("sftp://Build box/docs") shouldBe true
        mentions("cnc-dev") shouldBe false
    }

    @Test
    fun `a collapsed entry row's description follows a rename`() {
        NetworkLocationNames.updateSftp(listOf(location))
        composeTestRule.setContent {
            PreviewWrapper {
                ClipboardEntryRow(
                    entry = clip,
                    workspaceType = Workspace.Type.EXPLORER,
                    onPasteClick = {},
                    onEntryClick = {},
                    showOrigin = false,
                )
            }
        }
        exists("sftp://cnc-dev/docs/a.txt") shouldBe true

        rename("Build box")

        exists("sftp://Build box/docs/a.txt") shouldBe true
        mentions("cnc-dev") shouldBe false
    }

    @Test
    fun `an expanded entry row's description follows a rename`() {
        NetworkLocationNames.updateSftp(listOf(location))
        composeTestRule.setContent {
            PreviewWrapper {
                ClipboardEntryRow(
                    entry = clip,
                    workspaceType = Workspace.Type.EXPLORER,
                    onPasteClick = {},
                    onEntryClick = {},
                    showOrigin = true,
                )
            }
        }
        exists("sftp://cnc-dev/docs/a.txt") shouldBe true

        rename("Build box")

        exists("sftp://Build box/docs/a.txt") shouldBe true
        mentions("cnc-dev") shouldBe false
    }
}
