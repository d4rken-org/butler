package eu.darken.butler.common.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.progress.Progress
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class GatewaySwitchDeleteTest : BaseTest() {

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A"

    private lateinit var localGateway: LocalGateway
    private lateinit var safGateway: SAFGateway
    private lateinit var archiveGateway: eu.darken.butler.common.files.archive.ArchiveGateway
    private lateinit var safLocationManager: SAFLocationManager
    private lateinit var testScope: TestScope
    private lateinit var gatewaySwitch: GatewaySwitch

    private val localPath1 = LocalPath.build("dir", "file1")
    private val localPath2 = LocalPath.build("dir", "file2")
    private val safPath1 = SAFPath.build(treeUri, "dir", "file3")

    private val localLookup1 = LocalPathLookup(
        lookedUp = localPath1,
        fileType = FileType.FILE,
        size = 100L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
        target = null,
        ownership = null,
        permissions = null,
        createdAt = null,
    )
    private val localLookup2 = LocalPathLookup(
        lookedUp = localPath2,
        fileType = FileType.FILE,
        size = 50L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
        target = null,
        ownership = null,
        permissions = null,
        createdAt = null,
    )
    private val safLookup1 = SAFPathLookup(
        lookedUp = safPath1,
        fileType = FileType.FILE,
        size = 25L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
    )

    @Before
    fun setup() {
        localGateway = mockk(relaxed = true)
        safGateway = mockk(relaxed = true)
        archiveGateway = mockk(relaxed = true)
        safLocationManager = mockk(relaxed = true)
        testScope = TestScope()
        gatewaySwitch = GatewaySwitch(
            appScope = testScope,
            dispatcherProvider = TestDispatcherProvider(),
            safGateway = safGateway,
            localGateway = localGateway,
            archiveGateway = archiveGateway,
            safLocationManager = safLocationManager,
            proxyPfdFactory = mockk(relaxed = true),
        )
    }

    @Test
    fun `mixed type delete emits exactly one Completed with aggregated results`() = runTest {
        coEvery { localGateway.delete(any<Set<LocalPath>>(), any()) } returns flowOf(
            DeleteAction.State.Active(target = localLookup1, primaryProgress = Progress.Data(), deletedBytes = 100L),
            DeleteAction.State.Completed(deleted = setOf(localLookup1, localLookup2), skipped = emptySet()),
        )
        coEvery { safGateway.delete(any<Set<SAFPath>>(), any()) } returns flowOf(
            DeleteAction.State.Active(target = safLookup1, primaryProgress = Progress.Data(), deletedBytes = 10L),
            DeleteAction.State.Completed(deleted = emptySet(), skipped = setOf(safLookup1)),
        )

        val states = gatewaySwitch.delete(
            targets = setOf(localPath1, localPath2, safPath1),
            options = DeleteAction.Options(),
        ).toList()

        val completed = states.filterIsInstance<DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>>()
        completed.size shouldBe 1
        states.last().shouldBeInstanceOf<DeleteAction.State.Completed<*, *>>()

        completed.single().deleted shouldBe setOf(localLookup1, localLookup2)
        completed.single().skipped shouldBe setOf(safLookup1)
    }

    @Test
    fun `mixed type delete offsets active progress across groups`() = runTest {
        coEvery { localGateway.delete(any<Set<LocalPath>>(), any()) } returns flowOf(
            DeleteAction.State.Active(target = localLookup1, primaryProgress = Progress.Data(), deletedBytes = 100L),
            // bytesTotal is derived from deleted lookup sizes: 100 + 50 = 150
            DeleteAction.State.Completed(deleted = setOf(localLookup1, localLookup2), skipped = emptySet()),
        )
        coEvery { safGateway.delete(any<Set<SAFPath>>(), any()) } returns flowOf(
            DeleteAction.State.Active(target = safLookup1, primaryProgress = Progress.Data(), deletedBytes = 10L),
            DeleteAction.State.Completed(deleted = setOf(safLookup1), skipped = emptySet()),
        )

        val states = gatewaySwitch.delete(
            targets = setOf(localPath1, localPath2, safPath1),
            options = DeleteAction.Options(),
        ).toList()

        val activeBytes = states
            .filterIsInstance<DeleteAction.State.Active<APath<*>, APathLookup<APath<*>>>>()
            .map { it.deletedBytes }
        activeBytes shouldContainExactly listOf(100L, 160L)
    }

    @Test
    fun `active progress stays monotonic when items end up skipped`() = runTest {
        // Group 1 reports Active progress but everything is skipped: Completed.bytesTotal is 0.
        coEvery { localGateway.delete(any<Set<LocalPath>>(), any()) } returns flowOf(
            DeleteAction.State.Active(target = localLookup1, primaryProgress = Progress.Data(), deletedBytes = 100L),
            DeleteAction.State.Completed(deleted = emptySet(), skipped = setOf(localLookup1, localLookup2)),
        )
        coEvery { safGateway.delete(any<Set<SAFPath>>(), any()) } returns flowOf(
            DeleteAction.State.Active(target = safLookup1, primaryProgress = Progress.Data(), deletedBytes = 10L),
            DeleteAction.State.Completed(deleted = setOf(safLookup1), skipped = emptySet()),
        )

        val states = gatewaySwitch.delete(
            targets = setOf(localPath1, localPath2, safPath1),
            options = DeleteAction.Options(),
        ).toList()

        val activeBytes = states
            .filterIsInstance<DeleteAction.State.Active<APath<*>, APathLookup<APath<*>>>>()
            .map { it.deletedBytes }
        activeBytes shouldContainExactly listOf(100L, 110L)
        activeBytes shouldBe activeBytes.sorted()
    }

    @Test
    fun `single type delete emits exactly one Completed`() = runTest {
        coEvery { localGateway.delete(any<Set<LocalPath>>(), any()) } returns flowOf(
            DeleteAction.State.Active(target = localLookup1, primaryProgress = Progress.Data(), deletedBytes = 100L),
            DeleteAction.State.Completed(deleted = setOf(localLookup1), skipped = emptySet()),
        )

        val states = gatewaySwitch.delete(
            targets = setOf(localPath1),
            options = DeleteAction.Options(),
        ).toList()

        val completed = states.filterIsInstance<DeleteAction.State.Completed<APath<*>, APathLookup<APath<*>>>>()
        completed.size shouldBe 1
        completed.single().deleted shouldBe setOf(localLookup1)
    }
}
