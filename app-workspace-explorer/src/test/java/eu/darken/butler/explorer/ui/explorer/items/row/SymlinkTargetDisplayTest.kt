package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.ui.test.hasText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.network.NetworkLocationNames
import eu.darken.butler.common.files.sftp.SftpPathLookup
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.FileTypeClassifier
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** From the classified lookup to the rendered row, as a directory listing produces it. */
class SymlinkTargetDisplayTest : ComposeTest() {

    private val locationId = Uuid.parse("c0360031-0000-4000-8000-000000000001")

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

    @After
    fun resetNames() = NetworkLocationNames.clear()

    private fun render(lookup: APathLookup<*>) {
        val item = FileTypeClassifier().classify(lookup) as ExplorerItem.SymbolicLink
        composeTestRule.setContent {
            PreviewWrapper {
                SymlinkFileRow(
                    item = item,
                    density = ExplorerViewStyle.Density.COMFORTABLE,
                    isSelected = false,
                    onToggleSelection = {},
                    onClick = {},
                    showSelection = false,
                )
            }
        }
    }

    private fun exists(text: String) = composeTestRule
        .onAllNodes(hasText(text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    @Test
    fun `an SFTP target is named after its location`() {
        NetworkLocationNames.updateSftp(listOf(location))

        render(
            SftpPathLookup(
                lookedUp = SftpPath(locationId, listOf("usr", "local", "bin", "tool")),
                fileType = FileType.SYMBOLIC_LINK,
                size = null,
                modifiedAt = null,
                target = SftpPath(locationId, listOf("usr", "bin")),
            )
        )

        exists("→ sftp://cnc-dev/usr/bin") shouldBe true
        exists("→ sftp://$locationId/usr/bin") shouldBe false
    }

    @Test
    fun `a local target is shown as its path`() {
        NetworkLocationNames.updateSftp(listOf(location))

        render(
            LocalPathLookup(
                lookedUp = LocalPath.build("/usr/local/bin/tool"),
                fileType = FileType.SYMBOLIC_LINK,
                size = null,
                modifiedAt = null,
                target = LocalPath.build("/usr/bin"),
            )
        )

        exists("→ /usr/bin") shouldBe true
    }
}
