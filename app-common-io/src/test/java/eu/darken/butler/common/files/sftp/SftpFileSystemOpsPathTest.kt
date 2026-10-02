package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.ssh.SftpCapacity
import eu.darken.ssh.SftpEntry
import eu.darken.ssh.SftpFileType
import eu.darken.ssh.SftpSession
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid
import eu.darken.ssh.SftpPath as ServerPath

/** The mapping between location paths and server paths, the one place where separators exist. */
class SftpFileSystemOpsPathTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val root = ServerPath(listOf("home", "darken"))

    @Test
    fun `the root is resolved by the server in every form`() = runTest {
        val session = mockk<SftpSession> {
            coEvery { canonicalize(".") } returns root
            coEvery { canonicalize("media") } returns root.child("media")
            coEvery { canonicalize("/srv/media") } returns ServerPath(listOf("srv", "media"))
        }

        SftpRoot.resolve(session, "") shouldBe root
        SftpRoot.resolve(session, "media") shouldBe root.child("media")
        SftpRoot.resolve(session, "/srv/media") shouldBe ServerPath(listOf("srv", "media"))
    }

    @Test
    fun `segments are appended to the resolved root`() {
        SftpRoot.toServer(root, SftpPath.root(locationId)).toString() shouldBe "/home/darken"
        SftpRoot.toServer(root, SftpPath(locationId, listOf("a\\b", "c d"))).toString() shouldBe "/home/darken/a\\b/c d"
        SftpRoot.toServer(ServerPath.Root, SftpPath(locationId, listOf("etc"))).toString() shouldBe "/etc"
    }

    @Test
    fun `a server path maps back only from inside the root`() {
        SftpRoot.toLocation(root, locationId, root.child("a")) shouldBe SftpPath(locationId, listOf("a"))
        SftpRoot.toLocation(root, locationId, root) shouldBe SftpPath.root(locationId)
        SftpRoot.toLocation(root, locationId, ServerPath(listOf("home"))) shouldBe null
        SftpRoot.toLocation(root, locationId, ServerPath(listOf("home", "other"))) shouldBe null
    }

    @Test
    fun `link targets resolve lexically against the link's directory`() {
        val directory = root.child("photos")

        SftpRoot.resolveLinkTarget(directory, "2024") shouldBe root.child("photos").child("2024")
        SftpRoot.resolveLinkTarget(directory, "../shared/./x") shouldBe root.child("shared").child("x")
        SftpRoot.resolveLinkTarget(directory, ".") shouldBe directory
        SftpRoot.resolveLinkTarget(directory, "/srv//media/") shouldBe ServerPath(listOf("srv", "media"))
        SftpRoot.resolveLinkTarget(directory, "../../../../..") shouldBe ServerPath.Root
    }

    @Test
    fun `ids are read as unsigned and both are needed for an ownership`() {
        fun entry(uid: Int?, gid: Int?) = SftpEntry(
            path = root,
            type = SftpFileType.FILE,
            size = 0,
            modifiedAt = Instant.fromEpochSeconds(0),
            permissions = null,
            uid = uid,
            gid = gid,
        )

        SftpFileSystemOps.ownershipOf(entry(1000, 100)) shouldBe Ownership(1000L, 100L)
        SftpFileSystemOps.ownershipOf(entry(-2, -2)) shouldBe Ownership(4294967294L, 4294967294L)
        SftpFileSystemOps.ownershipOf(entry(1000, null)) shouldBe null
    }

    @Test
    fun `capacity is asked for the requested folder, not the location root`() = runTest {
        val requested = slot<ServerPath>()
        val session = mockk<SftpSession> {
            coEvery { capacity(capture(requested)) } returns SftpCapacity(totalBytes = 100, freeBytes = 40)
        }
        val lease = SftpConnectionPool.Lease(
            location = testSftpLocation(locationId),
            session = session,
            root = root,
            onRelease = {},
        )
        val pool = mockk<SftpConnectionPool> {
            coEvery { use<Any?>(any(), any(), any()) } coAnswers {
                thirdArg<suspend (SftpConnectionPool.Lease) -> Any?>().invoke(lease)
            }
        }

        val fileSystem = SftpFileSystemOps(pool, TestDispatcherProvider())
            .getFileSystem(SftpPath(locationId, listOf("mnt", "media")))

        // Proves the fake was reached: getFileSystem swallows errors into an empty FileSystem
        fileSystem.freeSpace shouldBe 40L
        requested.captured shouldBe ServerPath(listOf("home", "darken", "mnt", "media"))
    }
}
