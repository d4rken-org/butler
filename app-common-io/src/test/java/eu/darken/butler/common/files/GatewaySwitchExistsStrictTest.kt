package eu.darken.butler.common.files

import eu.darken.butler.common.files.archive.ArchiveGateway
import eu.darken.butler.common.files.io.ProxyPfdFactory
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.smb.SmbGateway
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.uuid.Uuid

/**
 * AUTO retries an unanswered probe on the alternative path, which only exists for the local/SAF
 * split. Every other type has to come back as "could not tell" rather than as a failure.
 */
class GatewaySwitchExistsStrictTest : BaseTest() {

    private val safGateway: SAFGateway = mockk(relaxed = true)
    private val localGateway: LocalGateway = mockk(relaxed = true)
    private val archiveGateway: ArchiveGateway = mockk(relaxed = true)
    private val smbGateway: SmbGateway = mockk(relaxed = true)
    private val safLocationManager: SAFLocationManager = mockk(relaxed = true)

    private val gatewaySwitch = GatewaySwitch(
        appScope = TestScope(),
        dispatcherProvider = TestDispatcherProvider(),
        safGateway = safGateway,
        localGateway = localGateway,
        archiveGateway = archiveGateway,
        smbGateway = smbGateway,
        safLocationManager = safLocationManager,
        proxyPfdFactory = mockk<ProxyPfdFactory>(relaxed = true),
    )

    @Test
    fun `a definitive answer is passed through`() = runTest {
        val path = LocalPath.build("/sdcard/gone.txt")
        coEvery { localGateway.existsStrict(path) } returns Existence.ABSENT

        gatewaySwitch.existsStrict(path) shouldBe Existence.ABSENT
        gatewaySwitch.existsStrict(path, GatewaySwitch.Type.AUTO) shouldBe Existence.ABSENT

        coVerify(exactly = 0) { safGateway.existsStrict(any()) }
    }

    @Test
    fun `AUTO retries an unanswered local probe on the SAF side`() = runTest {
        val path = LocalPath.build("/storage/emulated/0/Music")
        val alternative = SAFPath.build(SAF_TREE_ROOT, "Music")
        coEvery { localGateway.existsStrict(path) } returns Existence.UNKNOWN
        coEvery { safLocationManager.toSAFPath(path) } returns alternative
        coEvery { safGateway.existsStrict(alternative) } returns Existence.PRESENT

        gatewaySwitch.existsStrict(path, GatewaySwitch.Type.AUTO) shouldBe Existence.PRESENT
    }

    @Test
    fun `CURRENT keeps an unanswered probe unanswered`() = runTest {
        val path = LocalPath.build("/storage/emulated/0/Music")
        coEvery { localGateway.existsStrict(path) } returns Existence.UNKNOWN

        gatewaySwitch.existsStrict(path, GatewaySwitch.Type.CURRENT) shouldBe Existence.UNKNOWN

        coVerify(exactly = 0) { safGateway.existsStrict(any()) }
    }

    /** No mapped SAF location means there is no alternative to ask, not a failure to report. */
    @Test
    fun `an unmappable path stays unanswered`() = runTest {
        val path = LocalPath.build("/data/data/eu.darken.butler")
        coEvery { localGateway.existsStrict(path) } returns Existence.UNKNOWN
        coEvery { safLocationManager.toSAFPath(path) } returns null

        gatewaySwitch.existsStrict(path, GatewaySwitch.Type.AUTO) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `a failing alternative leaves the original answer standing`() = runTest {
        val path = SAFPath.build(SAF_TREE_ROOT, "Music")
        val alternative = LocalPath.build("/storage/emulated/0/Music")
        coEvery { safGateway.existsStrict(path) } returns Existence.UNKNOWN
        coEvery { safLocationManager.toLocalPath(path) } returns alternative
        coEvery { localGateway.existsStrict(alternative) } throws IllegalStateException("boom")

        gatewaySwitch.existsStrict(path, GatewaySwitch.Type.AUTO) shouldBe Existence.UNKNOWN
    }

    /** toAlternative() has none for these two, so AUTO must not turn UNKNOWN into a thrown error. */
    @Test
    fun `an unanswered network probe is not a failure`() = runTest {
        val path = SmbPath(LOCATION_ID, listOf("media"))
        coEvery { smbGateway.existsStrict(path) } returns Existence.UNKNOWN

        gatewaySwitch.existsStrict(path, GatewaySwitch.Type.AUTO) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `an unanswered archive probe is not a failure`() = runTest {
        val path = ArchivePath(LocalPath.build("/sdcard/archive.zip"), listOf("docs"))
        coEvery { archiveGateway.existsStrict(path) } returns Existence.UNKNOWN

        gatewaySwitch.existsStrict(path, GatewaySwitch.Type.AUTO) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `cancellation propagates`() = runTest {
        val path = LocalPath.build("/sdcard/file.txt")
        coEvery { localGateway.existsStrict(path) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> { gatewaySwitch.existsStrict(path) }
    }

    companion object {
        private val LOCATION_ID = Uuid.parse("11111111-2222-3333-4444-555555555555")
        private const val SAF_TREE_ROOT = "content://com.android.externalstorage.documents/tree/primary%3A"
    }
}
