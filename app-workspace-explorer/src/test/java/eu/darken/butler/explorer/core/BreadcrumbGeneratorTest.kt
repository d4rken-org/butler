package eu.darken.butler.explorer.core

import android.content.Context
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BreadcrumbGeneratorTest : BaseTest() {

    private val locationId = Uuid.parse("66666666-7777-8888-9999-000000000000")

    private val location = SftpLocation(
        id = locationId,
        label = "Build server",
        host = "build.lan",
        username = "darken",
        basePath = "/srv",
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

    private val webDavLocationId = Uuid.parse("77777777-8888-9999-0000-111111111111")

    private val webDavLocation = WebDavLocation(
        id = webDavLocationId,
        label = "Nextcloud",
        url = "https://cloud.example.com/remote.php/dav/files/alice/",
        authType = WebDavLocation.AuthType.BASIC,
        username = "alice",
        rememberCredential = true,
        credentialVersion = 1,
        pinnedCertificate = null,
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private val sftpLocationManager = mockk<SftpLocationManager>()
    private val webDavLocationManager = mockk<WebDavLocationManager>()

    private val generator = BreadcrumbGenerator(
        safLocationManager = mockk(),
        smbLocationManager = mockk(),
        sftpLocationManager = sftpLocationManager,
        webDavLocationManager = webDavLocationManager,
        trashSettings = mockk(),
    )

    private val context = mockk<Context>()

    @Test
    fun `an SFTP folder is Home, Network, the server, then each folder`() = runTest {
        coEvery { sftpLocationManager.get(locationId) } returns location
        val path = SftpPath(locationId, listOf("builds", "2026"))

        val crumbs = generator.getBreadcrumbs(ExplorerLocation.Directory(path = path))

        crumbs.map { it.target } shouldBe listOf(
            BreadcrumbGenerator.HOME.target,
            BreadcrumbGenerator.NETWORK.target,
            ExplorerNavigation.Target.Directory(SftpPath.root(locationId)),
            ExplorerNavigation.Target.Directory(SftpPath(locationId, listOf("builds"))),
            ExplorerNavigation.Target.Directory(path),
        )
        crumbs.drop(2).map { it.label.get(context) } shouldBe listOf("Build server", "builds", "2026")
    }

    @Test
    fun `a removed SFTP location still gets a root crumb`() = runTest {
        coEvery { sftpLocationManager.get(locationId) } returns null

        val crumbs = generator.getBreadcrumbs(ExplorerLocation.Directory(path = SftpPath.root(locationId)))

        crumbs.map { it.target } shouldBe listOf(
            BreadcrumbGenerator.HOME.target,
            BreadcrumbGenerator.NETWORK.target,
            ExplorerNavigation.Target.Directory(SftpPath.root(locationId)),
        )
        crumbs.last().label.get(context) shouldBe locationId.toString()
    }

    @Test
    fun `a WebDAV folder is Home, Network, the location, then each folder`() = runTest {
        coEvery { webDavLocationManager.get(webDavLocationId) } returns webDavLocation
        val path = WebDavPath(webDavLocationId, listOf("Photos", "2026"))

        val crumbs = generator.getBreadcrumbs(ExplorerLocation.Directory(path = path))

        crumbs.map { it.target } shouldBe listOf(
            BreadcrumbGenerator.HOME.target,
            BreadcrumbGenerator.NETWORK.target,
            ExplorerNavigation.Target.Directory(WebDavPath.root(webDavLocationId)),
            ExplorerNavigation.Target.Directory(WebDavPath(webDavLocationId, listOf("Photos"))),
            ExplorerNavigation.Target.Directory(path),
        )
        crumbs.drop(2).map { it.label.get(context) } shouldBe listOf("Nextcloud", "Photos", "2026")
    }

    @Test
    fun `a removed WebDAV location still gets a root crumb`() = runTest {
        coEvery { webDavLocationManager.get(webDavLocationId) } returns null

        val crumbs = generator.getBreadcrumbs(ExplorerLocation.Directory(path = WebDavPath.root(webDavLocationId)))

        crumbs.map { it.target } shouldBe listOf(
            BreadcrumbGenerator.HOME.target,
            BreadcrumbGenerator.NETWORK.target,
            ExplorerNavigation.Target.Directory(WebDavPath.root(webDavLocationId)),
        )
        crumbs.last().label.get(context) shouldBe webDavLocationId.toString()
    }
}
