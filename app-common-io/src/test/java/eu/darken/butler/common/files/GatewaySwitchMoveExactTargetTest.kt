package eu.darken.butler.common.files

import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.archive.ArchiveGateway
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.io.ProxyPfdFactory
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.smb.SmbGateway
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class GatewaySwitchMoveExactTargetTest : BaseTest() {

    private val localGateway: LocalGateway = mockk(relaxed = true)

    private val gatewaySwitch = GatewaySwitch(
        appScope = TestScope(),
        dispatcherProvider = TestDispatcherProvider(),
        safGateway = mockk<SAFGateway>(relaxed = true),
        localGateway = localGateway,
        archiveGateway = mockk<ArchiveGateway>(relaxed = true),
        smbGateway = mockk<SmbGateway>(relaxed = true),
        sftpGateway = mockk(relaxed = true),
        webdavGateway = mockk(relaxed = true),
        safLocationManager = mockk<SAFLocationManager>(relaxed = true),
        proxyPfdFactory = mockk<ProxyPfdFactory>(relaxed = true),
    )

    private val localSource = LocalPath.build("/storage/emulated/0/Download/a.txt")
    private val destination = LocalPath.build("/storage/emulated/0/Download/Photos")
    private val forwardedOptions = slot<MoveAction.Options>()

    init {
        coEvery {
            localGateway.move(any<Set<LocalPath>>(), any<LocalPath>(), any(), capture(forwardedOptions))
        } returns emptyFlow()
    }

    @Test
    fun `a mixed-type multi-source move never forwards exactTarget`() = runTest {
        // The archive group fails right after the local group ran, without touching any mock.
        val archiveSource = ArchivePath(LocalPath.build("/storage/emulated/0/Download/b.zip"), listOf("c.txt"))
        val sources: Set<APath<*>> = linkedSetOf(localSource, archiveSource)

        shouldThrow<WriteException> {
            gatewaySwitch.move(sources, destination, null, MoveAction.Options(exactTarget = true)).toList()
        }

        forwardedOptions.captured.exactTarget shouldBe false
    }

    @Test
    fun `a single-source move keeps exactTarget`() = runTest {
        gatewaySwitch.move(setOf(localSource), destination, null, MoveAction.Options(exactTarget = true)).toList()

        forwardedOptions.captured.exactTarget shouldBe true
    }
}
