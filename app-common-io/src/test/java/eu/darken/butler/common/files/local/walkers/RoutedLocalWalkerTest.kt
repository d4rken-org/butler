package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.CapabilitySnapshot
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.ModeSession
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.RouteDecision
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.sharedresource.KeepAlive
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException
import kotlin.time.Instant

/**
 * Coverage for [RoutedLocalWalker]'s route handling on top of the shared [LocalWalkerCore]
 * traversal: boundary splicing, mode-context inheritance, failure-driven escalation and
 * streaming delegation, with mocked routing collaborators.
 */
class RoutedLocalWalkerTest : BaseTest() {

    private fun p(path: String): LocalPath = LocalPath.build(path)

    private fun lookup(path: String, type: FileType): LocalPathLookup = LocalPathLookup(
        lookedUp = p(path),
        fileType = type,
        size = 0L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun dir(path: String) = lookup(path, FileType.DIRECTORY)
    private fun file(path: String) = lookup(path, FileType.FILE)

    private fun mockOps(): FileSystemOps<LocalPath, LocalPathLookup> = mockk()

    private fun directPolicy(): LocalPathRoutingPolicy = mockk {
        coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.DIRECT)
        every { knownRouteBoundariesUnder(any()) } returns emptySet()
    }

    private fun factoryOf(vararg sessions: Pair<AccessMode, ModeSession>): ModeSessionFactory = mockk {
        sessions.forEach { (mode, session) ->
            coEvery { open(mode) } returns session
        }
    }

    private fun walker(
        start: LocalPath,
        policy: LocalPathRoutingPolicy,
        factory: ModeSessionFactory,
        caps: CapabilitySnapshot = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = false),
        onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
        streamingEligible: Boolean = false,
    ) = RoutedLocalWalker(
        routingPolicy = policy,
        sessionFactory = factory,
        caps = caps,
        start = start,
        lookupOptions = LookupOptions.BASE,
        onError = onError,
        streamingEligible = streamingEligible,
    )

    @Test
    fun `hidden boundary reported at multiple depths is spliced and walked exactly once`() = runTest {
        val start = p("/sdcard")
        val android = p("/sdcard/Android")
        val boundary = p("/sdcard/Android/data")

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/sdcard")
        coEvery { directOps.lookupFiles(start, any()) } returns listOf(dir("/sdcard/Android"))
        // The OS hides Android/data from the listing of its parent
        coEvery { directOps.lookupFiles(android, any()) } returns emptyList()

        val rootOps = mockOps()
        coEvery { rootOps.lookup(boundary, any()) } returns dir("/sdcard/Android/data")
        coEvery { rootOps.lookupFiles(boundary, any()) } returns listOf(file("/sdcard/Android/data/secret.txt"))

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } answers {
                if (firstArg<LocalPath>().isDescendantOfOrSelf(boundary)) {
                    RouteDecision.Allowed(AccessMode.ROOT)
                } else {
                    RouteDecision.Allowed(AccessMode.DIRECT)
                }
            }
            // Reported for every listed directory: at walk-root depth AND when the parent is listed
            every { knownRouteBoundariesUnder(any()) } returns setOf(boundary)
        }
        val factory = factoryOf(
            AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, null),
            AccessMode.ROOT to ModeSession(AccessMode.ROOT, rootOps, null, null),
        )

        val emitted = walker(start, policy, factory).toList().map { it.lookedUp.path }

        emitted shouldContainExactlyInAnyOrder listOf(
            "/sdcard/Android",
            "/sdcard/Android/data",
            "/sdcard/Android/data/secret.txt",
        )
        emitted.count { it == "/sdcard/Android/data" } shouldBe 1
        coVerify(exactly = 1) { rootOps.lookup(boundary, any()) }
        coVerify(exactly = 1) { rootOps.lookupFiles(boundary, any()) }
    }

    @Test
    fun `children inherit the parent route without per-child router consultation`() = runTest {
        val start = p("/root")

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/root")
        coEvery { directOps.lookupFiles(start, any()) } returns listOf(dir("/root/a"), file("/root/top.txt"))
        coEvery { directOps.lookupFiles(p("/root/a"), any()) } returns listOf(dir("/root/a/b"), file("/root/a/f.txt"))
        coEvery { directOps.lookupFiles(p("/root/a/b"), any()) } returns listOf(file("/root/a/b/deep.txt"))

        val policy = directPolicy()
        val factory = factoryOf(AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, null))

        val emitted = walker(start, policy, factory).toList().map { it.lookedUp.path }

        emitted shouldContainExactlyInAnyOrder listOf(
            "/root/a",
            "/root/top.txt",
            "/root/a/b",
            "/root/a/f.txt",
            "/root/a/b/deep.txt",
        )
        // The router is consulted once at the start path; every child inherits its parent's route
        coVerify(exactly = 1) { policy.classify(any(), any(), any()) }
        coVerify(exactly = 1) { factory.open(any()) }
    }

    @Test
    fun `listing failure escalates through routeAfterFailure without reporting an error`() = runTest {
        val start = p("/sdcard/dir")

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/sdcard/dir")
        coEvery { directOps.lookupFiles(start, any()) } throws IOException("denied")

        val rootOps = mockOps()
        coEvery { rootOps.lookupFiles(start, any()) } returns listOf(file("/sdcard/dir/child.txt"))

        val policy = directPolicy()
        val factory = factoryOf(
            AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, null),
            AccessMode.ROOT to ModeSession(AccessMode.ROOT, rootOps, null, null),
        )
        val errors = mutableListOf<Pair<LocalPathLookup, Exception>>()

        val emitted = walker(
            start = start,
            policy = policy,
            factory = factory,
            onError = { lookup, e ->
                errors += lookup to e
                true
            },
        ).toList()

        emitted.map { it.lookedUp.path } shouldContainExactly listOf("/sdcard/dir/child.txt")
        errors.shouldBeEmpty()
        coVerify(exactly = 1) { factory.open(AccessMode.ROOT) }
    }

    @Test
    fun `exhausted escalation surfaces the original exception via onError`() = runTest {
        val start = p("/sdcard/dir")
        val original = IOException("original failure")

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/sdcard/dir")
        coEvery { directOps.lookupFiles(start, any()) } throws original

        val policy = directPolicy()
        val factory = factoryOf(AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, null))
        val errors = mutableListOf<Pair<LocalPathLookup, Exception>>()

        val emitted = walker(
            start = start,
            policy = policy,
            factory = factory,
            caps = CapabilitySnapshot.fixed(hasRoot = false, hasAdb = false),
            onError = { lookup, e ->
                errors += lookup to e
                true
            },
        ).toList()

        emitted.shouldBeEmpty()
        errors.single().first.lookedUp shouldBe start
        errors.single().second shouldBeSameInstanceAs original
        coVerify(exactly = 0) { factory.open(AccessMode.ROOT) }
        coVerify(exactly = 0) { factory.open(AccessMode.ADB) }
    }

    @Test
    fun `streaming-eligible escalated subtree is delegated to the client walk`() = runTest {
        val start = p("/data/subtree")

        val client = mockk<FileOpsClient>()
        coEvery { client.lookup(start, any()) } returns dir("/data/subtree")
        every { client.walk(any(), any(), any(), any()) } returns flowOf(
            file("/data/subtree/a.txt"),
            file("/data/subtree/sub/b.txt"),
        )

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.ROOT)
            every { knownRouteBoundariesUnder(any()) } returns emptySet()
        }
        val factory = factoryOf(AccessMode.ROOT to ModeSession(AccessMode.ROOT, client, null, null))

        val emitted = walker(start, policy, factory, streamingEligible = true).toList()

        emitted.map { it.lookedUp.path } shouldContainExactly listOf(
            "/data/subtree/a.txt",
            "/data/subtree/sub/b.txt",
        )
        verify(exactly = 1) { client.walk(start, any(), any(), null) }
        coVerify(exactly = 0) { client.lookupFiles(any(), any()) }
    }

    @Test
    fun `non-streamable escalated subtree is listed per directory`() = runTest {
        val start = p("/data/subtree")

        val client = mockk<FileOpsClient>()
        coEvery { client.lookup(start, any()) } returns dir("/data/subtree")
        coEvery { client.lookupFiles(start, any()) } returns listOf(file("/data/subtree/a.txt"))

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.ROOT)
            every { knownRouteBoundariesUnder(any()) } returns emptySet()
        }
        val factory = factoryOf(AccessMode.ROOT to ModeSession(AccessMode.ROOT, client, null, null))

        val emitted = walker(start, policy, factory, streamingEligible = false).toList()

        emitted.map { it.lookedUp.path } shouldContainExactly listOf("/data/subtree/a.txt")
        verify(exactly = 0) { client.walk(any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.lookupFiles(start, any()) }
    }

    @Test
    fun `mode sessions are closed when collection is cancelled`() = runTest {
        val start = p("/root")
        val lease = RecordingKeepAlive()

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/root")
        coEvery { directOps.lookupFiles(start, any()) } returns listOf(
            file("/root/f1.txt"),
            file("/root/f2.txt"),
            file("/root/f3.txt"),
            file("/root/f4.txt"),
        )

        val policy = directPolicy()
        val factory = factoryOf(AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, lease))

        val emitted = walker(start, policy, factory).take(2).toList()

        emitted.map { it.lookedUp.path } shouldContainExactly listOf("/root/f1.txt", "/root/f2.txt")
        lease.isClosed.shouldBeTrue()
    }

    private class RecordingKeepAlive : KeepAlive {
        override val resourceId: String = "test"
        override var isClosed: Boolean = false
            private set

        override fun close() {
            isClosed = true
        }
    }
}
