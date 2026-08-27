package eu.darken.butler.common.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.archive.ArchiveGateway
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException
import kotlin.time.Instant

/**
 * Covers [GatewaySwitch]'s AUTO fallback: which gateway an operation actually reaches, and which
 * failure a caller is left holding when both sides fail.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class GatewaySwitchFallbackTest : BaseTest() {

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A"

    private lateinit var localGateway: LocalGateway
    private lateinit var safGateway: SAFGateway
    private lateinit var archiveGateway: ArchiveGateway
    private lateinit var safLocationManager: SAFLocationManager
    private lateinit var gatewaySwitch: GatewaySwitch

    private val localPath = LocalPath.build("storage", "emulated", "0", "file")
    private val safPath = SAFPath.build(treeUri, "file")

    private val safLookup = SAFPathLookup(
        lookedUp = safPath,
        fileType = FileType.FILE,
        size = 7L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
        target = null,
        ownership = null,
        permissions = null,
        createdAt = null,
    )

    @Before
    fun setup() {
        localGateway = mockk(relaxed = true)
        safGateway = mockk(relaxed = true)
        archiveGateway = mockk(relaxed = true)
        safLocationManager = mockk(relaxed = true)
        gatewaySwitch = GatewaySwitch(
            appScope = TestScope(),
            dispatcherProvider = TestDispatcherProvider(),
            safGateway = safGateway,
            localGateway = localGateway,
            archiveGateway = archiveGateway,
            smbGateway = mockk(relaxed = true),
            safLocationManager = safLocationManager,
            proxyPfdFactory = mockk(relaxed = true),
        )
    }

    @Test
    fun `the AUTO fallback hands the alternative path to the alternative gateway`() = runTest {
        // The mapped path is what the fallback gateway has to receive. Handing it the ORIGINAL path
        // sends a LocalPath into SAFGateway, which cannot do anything with it.
        every { safLocationManager.toSAFPath(localPath) } returns safPath
        coEvery { localGateway.lookup(any(), any()) } throws ReadException(path = localPath)
        coEvery { safGateway.lookup(safPath, any()) } returns safLookup

        gatewaySwitch.lookup(localPath, LookupOptions.BASE, GatewaySwitch.Type.AUTO) shouldBe safLookup
    }

    @Test
    fun `an unmappable alternative keeps the original failure`() = runTest {
        // toAlternative() used to run outside the inner try, so "Can't map to SAF" replaced the
        // actual reason the access failed and the caller never learned it.
        val original = ReadException(message = "the real reason", path = localPath)
        every { safLocationManager.toSAFPath(localPath) } returns null
        coEvery { localGateway.lookup(any(), any()) } throws original

        val thrown = shouldThrow<ReadException> {
            gatewaySwitch.lookup(localPath, LookupOptions.BASE, GatewaySwitch.Type.AUTO)
        }
        thrown shouldBe original
        // The mapping failure isn't lost, just demoted.
        (thrown.suppressed.size == 1) shouldBe true
    }

    @Test
    fun `a failing alternative keeps the original failure and attaches its own`() = runTest {
        val original = ReadException(message = "the real reason", path = localPath)
        val fallbackFailure = ReadException(message = "the alternative also failed", path = safPath)
        every { safLocationManager.toSAFPath(localPath) } returns safPath
        coEvery { localGateway.lookup(any(), any()) } throws original
        coEvery { safGateway.lookup(any(), any()) } throws fallbackFailure

        val thrown = shouldThrow<ReadException> {
            gatewaySwitch.lookup(localPath, LookupOptions.BASE, GatewaySwitch.Type.AUTO)
        }
        thrown shouldBe original
        thrown.suppressed.toList() shouldBe listOf(fallbackFailure)
    }

    @Test
    fun `exists surfaces the original failure, not the alternative gateway's`() = runTest {
        // exists() had no inner try at all, so the alternative's error escaped and the caller was
        // told about a gateway it never asked for.
        val original = ReadException(message = "the real reason", path = localPath)
        every { safLocationManager.toSAFPath(localPath) } returns safPath
        coEvery { localGateway.exists(any()) } throws original
        coEvery { safGateway.exists(any()) } throws ReadException(message = "alternative boom", path = safPath)

        val thrown = shouldThrow<ReadException> {
            gatewaySwitch.exists(localPath, GatewaySwitch.Type.AUTO)
        }
        thrown shouldBe original
    }

    @Test
    fun `a non-AUTO type never reaches the alternative gateway`() = runTest {
        val original = ReadException(message = "the real reason", path = localPath)
        coEvery { localGateway.lookup(any(), any()) } throws original

        shouldThrow<ReadException> {
            gatewaySwitch.lookup(localPath, LookupOptions.BASE, GatewaySwitch.Type.CURRENT)
        } shouldBe original
    }

    @Test
    fun `lookupFiles falls back to the alternative gateway too`() = runTest {
        every { safLocationManager.toSAFPath(localPath) } returns safPath
        coEvery { localGateway.lookupFiles(any(), any()) } throws ReadException(path = localPath)
        coEvery { safGateway.lookupFiles(safPath, any()) } returns listOf(safLookup)

        gatewaySwitch.lookupFiles(localPath, LookupOptions.BASE, GatewaySwitch.Type.AUTO) shouldBe
            listOf(safLookup)
    }

    @Test
    fun `a non-read failure is not retried on the alternative`() = runTest {
        // Only ReadException means "this gateway couldn't get at it". Anything else is a real error
        // and must not be masked by a second attempt somewhere else.
        every { safLocationManager.toSAFPath(localPath) } returns safPath
        coEvery { localGateway.lookup(any(), any()) } throws IOException("something else")
        coEvery { safGateway.lookup(any(), any()) } returns safLookup

        shouldThrow<IOException> {
            gatewaySwitch.lookup(localPath, LookupOptions.BASE, GatewaySwitch.Type.AUTO)
        }
    }

    @Test
    fun `createSymlink refuses a link and target of different path types`() = runTest {
        // The gateway comes from the link, so a mismatched target would reach a gateway that cannot
        // handle it at all.
        shouldThrow<IllegalArgumentException> { gatewaySwitch.createSymlink(localPath, safPath) }
        shouldThrow<IllegalArgumentException> { gatewaySwitch.createSymlink(safPath, localPath) }
    }

    @Test
    fun `createSymlink still works within one path type`() = runTest {
        val other = LocalPath.build("storage", "emulated", "0", "target")
        coEvery { localGateway.createSymlink(localPath, other) } returns true

        gatewaySwitch.createSymlink(localPath, other) shouldBe true
    }
}
