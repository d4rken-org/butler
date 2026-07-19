package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.ReadException
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
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flow
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
    private fun symlink(path: String) = lookup(path, FileType.SYMBOLIC_LINK)

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
        pathDoesNotContain: Set<String>? = null,
        onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
        followSymlinks: Boolean = false,
        streamingEligible: Boolean = false,
    ) = RoutedLocalWalker(
        routingPolicy = policy,
        sessionFactory = factory,
        caps = caps,
        start = start,
        lookupOptions = LookupOptions.BASE,
        pathDoesNotContain = pathDoesNotContain,
        onError = onError,
        followSymlinks = followSymlinks,
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
    fun `visible boundary child is listed through its own route`() = runTest {
        val start = p("/sdcard/Android")
        val boundary = p("/sdcard/Android/data")

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/sdcard/Android")
        // The OS SHOWS Android/data in the parent listing, but a DIRECT listing of it is empty
        coEvery { directOps.lookupFiles(start, any()) } returns listOf(
            dir("/sdcard/Android/data"),
            dir("/sdcard/Android/media"),
        )
        coEvery { directOps.lookupFiles(p("/sdcard/Android/media"), any()) } returns emptyList()

        val rootOps = mockOps()
        coEvery { rootOps.lookupFiles(boundary, any()) } returns listOf(file("/sdcard/Android/data/secret.txt"))

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } answers {
                if (firstArg<LocalPath>().isDescendantOfOrSelf(boundary)) {
                    RouteDecision.Allowed(AccessMode.ROOT)
                } else {
                    RouteDecision.Allowed(AccessMode.DIRECT)
                }
            }
            every { knownRouteBoundariesUnder(any()) } returns setOf(boundary)
        }
        val factory = factoryOf(
            AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, null),
            AccessMode.ROOT to ModeSession(AccessMode.ROOT, rootOps, null, null),
        )

        val emitted = walker(start, policy, factory).toList().map { it.lookedUp.path }

        emitted shouldContainExactlyInAnyOrder listOf(
            "/sdcard/Android/data",
            "/sdcard/Android/media",
            "/sdcard/Android/data/secret.txt",
        )
        emitted.count { it == "/sdcard/Android/data" } shouldBe 1
        // The visible boundary's content is listed through its own ROOT route, not the parent's
        coVerify(exactly = 1) { rootOps.lookupFiles(boundary, any()) }
        coVerify(exactly = 0) { directOps.lookupFiles(boundary, any()) }
    }

    @Test
    fun `followed symlink directory is listed through the target route`() = runTest {
        val start = p("/sdcard/dir")
        val link = p("/sdcard/dir/link")
        val target = p("/data/protected")

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/sdcard/dir")
        coEvery { directOps.canonicalize(start) } returns start
        coEvery { directOps.lookupFiles(start, any()) } returns listOf(symlink("/sdcard/dir/link"))
        coEvery { directOps.canonicalize(link) } returns target

        val rootOps = mockOps()
        coEvery { rootOps.lookup(target, any()) } returns dir("/data/protected")
        coEvery { rootOps.lookupFiles(link, any()) } returns listOf(file("/sdcard/dir/link/inner.txt"))

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } answers {
                if (firstArg<LocalPath>().isDescendantOfOrSelf(target)) {
                    RouteDecision.Allowed(AccessMode.ROOT)
                } else {
                    RouteDecision.Allowed(AccessMode.DIRECT)
                }
            }
            every { knownRouteBoundariesUnder(any()) } returns emptySet()
        }
        val factory = factoryOf(
            AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, null),
            AccessMode.ROOT to ModeSession(AccessMode.ROOT, rootOps, null, null),
        )

        val emitted = walker(start, policy, factory, followSymlinks = true).toList().map { it.lookedUp.path }

        emitted shouldContainExactlyInAnyOrder listOf(
            "/sdcard/dir/link",
            "/sdcard/dir/link/inner.txt",
        )
        // The followed link is traversed through the TARGET's ROOT route, not the parent's DIRECT one
        coVerify(exactly = 1) { rootOps.lookupFiles(link, any()) }
        coVerify(exactly = 0) { directOps.lookupFiles(link, any()) }
    }

    @Test
    fun `pathDoesNotContain filters children out of plain listings`() = runTest {
        val start = p("/root")

        val directOps = mockOps()
        coEvery { directOps.lookup(start, any()) } returns dir("/root")
        coEvery { directOps.lookupFiles(start, any()) } returns listOf(
            file("/root/keep.txt"),
            file("/root/cache.bin"),
            dir("/root/cachedir"),
        )

        val policy = directPolicy()
        val factory = factoryOf(AccessMode.DIRECT to ModeSession(AccessMode.DIRECT, directOps, null, null))

        val emitted = walker(start, policy, factory, pathDoesNotContain = setOf("cache")).toList()

        emitted.map { it.lookedUp.path } shouldContainExactly listOf("/root/keep.txt")
        // The excluded directory is not just unemitted, it is never traversed either
        coVerify(exactly = 0) { directOps.lookupFiles(p("/root/cachedir"), any()) }
    }

    @Test
    fun `pathDoesNotContain is forwarded into the delegated walk options`() = runTest {
        val start = p("/data/subtree")

        val client = mockk<FileOpsClient>()
        coEvery { client.lookup(start, any()) } returns dir("/data/subtree")
        val optionsSlot = slot<APathGateway.WalkOptions<LocalPath, LocalPathLookup>>()
        every { client.walk(any(), any(), capture(optionsSlot), any()) } returns flowOf(file("/data/subtree/a.txt"))

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.ROOT)
            every { knownRouteBoundariesUnder(any()) } returns emptySet()
        }
        val factory = factoryOf(AccessMode.ROOT to ModeSession(AccessMode.ROOT, client, null, null))

        walker(
            start = start,
            policy = policy,
            factory = factory,
            pathDoesNotContain = setOf("cache"),
            streamingEligible = true,
        ).toList()

        optionsSlot.captured.pathDoesNotContain shouldBe setOf("cache")
    }

    @Test
    fun `delegated stream failing before emission retries through the next escalation mode`() = runTest {
        val start = p("/data/subtree")

        val rootClient = mockk<FileOpsClient>()
        coEvery { rootClient.lookup(start, any()) } returns dir("/data/subtree")
        every { rootClient.walk(any(), any(), any(), any()) } returns flow { throw ReadException(path = start) }

        val adbClient = mockk<FileOpsClient>()
        every { adbClient.walk(any(), any(), any(), any()) } returns flowOf(file("/data/subtree/a.txt"))

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.ROOT)
            every { knownRouteBoundariesUnder(any()) } returns emptySet()
        }
        val factory = factoryOf(
            AccessMode.ROOT to ModeSession(AccessMode.ROOT, rootClient, null, null),
            AccessMode.ADB to ModeSession(AccessMode.ADB, adbClient, null, null),
        )
        val errors = mutableListOf<Pair<LocalPathLookup, Exception>>()

        val emitted = walker(
            start = start,
            policy = policy,
            factory = factory,
            caps = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = true),
            onError = { lookup, e ->
                errors += lookup to e
                true
            },
            streamingEligible = true,
        ).toList()

        emitted.map { it.lookedUp.path } shouldContainExactly listOf("/data/subtree/a.txt")
        errors.shouldBeEmpty()
        verify(exactly = 1) { rootClient.walk(any(), any(), any(), any()) }
        verify(exactly = 1) { adbClient.walk(any(), any(), any(), any()) }
    }

    @Test
    fun `delegated stream failing after emission reports onError without retry`() = runTest {
        val start = p("/data/subtree")
        val boom = ReadException(path = start)

        val rootClient = mockk<FileOpsClient>()
        coEvery { rootClient.lookup(start, any()) } returns dir("/data/subtree")
        every { rootClient.walk(any(), any(), any(), any()) } returns flow {
            emit(file("/data/subtree/a.txt"))
            throw boom
        }

        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.ROOT)
            every { knownRouteBoundariesUnder(any()) } returns emptySet()
        }
        val factory = factoryOf(AccessMode.ROOT to ModeSession(AccessMode.ROOT, rootClient, null, null))
        val errors = mutableListOf<Pair<LocalPathLookup, Exception>>()

        val emitted = walker(
            start = start,
            policy = policy,
            factory = factory,
            caps = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = true),
            onError = { lookup, e ->
                errors += lookup to e
                true
            },
            streamingEligible = true,
        ).toList()

        // Emitted items are never duplicated by a retry — the failure is reported instead
        emitted.map { it.lookedUp.path } shouldContainExactly listOf("/data/subtree/a.txt")
        errors.single().first.lookedUp shouldBe start
        errors.single().second shouldBeSameInstanceAs boom
        verify(exactly = 1) { rootClient.walk(any(), any(), any(), any()) }
        coVerify(exactly = 0) { factory.open(AccessMode.ADB) }
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
