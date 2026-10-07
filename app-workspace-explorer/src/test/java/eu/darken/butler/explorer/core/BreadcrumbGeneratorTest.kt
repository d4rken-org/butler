package eu.darken.butler.explorer.core

import android.content.Context
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
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

    private val sftpLocationManager = mockk<SftpLocationManager>()

    private val generator = BreadcrumbGenerator(
        safLocationManager = mockk(),
        smbLocationManager = mockk(),
        sftpLocationManager = sftpLocationManager,
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
}
