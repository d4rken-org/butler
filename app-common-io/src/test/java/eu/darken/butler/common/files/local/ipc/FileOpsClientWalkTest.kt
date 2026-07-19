package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

/**
 * Client-side mapping of the walkStreamV2 event protocol in [FileOpsClient.walk]: Item emission,
 * DirError-to-onError routing, FatalError propagation and truncation detection. The connection is
 * mocked with a real [toEventRemoteStream] pipe, so the full chunked wire format is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class FileOpsClientWalkTest : BaseTest() {

    private val path = LocalPath.build("/data/subtree")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = mockk<FileOpsConnection>()
    private val client = FileOpsClient(connection)

    @After
    fun teardown() {
        scope.cancel()
    }

    private fun lookup(name: String, type: FileType = FileType.FILE) = LocalPathLookup(
        lookedUp = LocalPath.build(name),
        fileType = type,
        size = 0L,
        modifiedAt = null,
    )

    private fun connectionStreams(events: List<WalkEvent>) {
        every { connection.walkStreamV2(any(), any(), any()) } answers {
            events.asFlow().toEventRemoteStream(scope)
        }
    }

    private suspend fun walk(
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup> = APathGateway.WalkOptions(),
        excludeSubtrees: List<LocalPath>? = null,
    ): List<LocalPathLookup> = withTimeout(10_000) {
        client.walk(path, LookupOptions.BASE, walkOptions, excludeSubtrees).toList()
    }

    @Test
    fun `Item events emit lookups and Done ends the walk cleanly`() {
        val a = lookup("/data/subtree/a.txt")
        val b = lookup("/data/subtree/b.txt")
        connectionStreams(listOf(WalkEvent.Item(a), WalkEvent.Item(b), WalkEvent.Done))

        runBlocking {
            walk() shouldContainExactly listOf(a, b)
        }
    }

    @Test
    fun `walk options and excludes are carried in the WalkSpec`() {
        connectionStreams(listOf(WalkEvent.Done))
        val excludes = listOf(LocalPath.build("/data/subtree/Android/data"))

        runBlocking {
            walk(
                walkOptions = APathGateway.WalkOptions(
                    pathDoesNotContain = setOf("cache"),
                    followSymlinks = true,
                ),
                excludeSubtrees = excludes,
            ).shouldBeEmpty()
        }

        verify(exactly = 1) {
            connection.walkStreamV2(
                path,
                LookupOptions.BASE,
                WalkSpec(
                    pathDoesNotContain = listOf("cache"),
                    followSymlinks = true,
                    excludeSubtrees = excludes,
                ),
            )
        }
    }

    @Test
    fun `DirError routes to onError and the walk continues when it returns true`() {
        val a = lookup("/data/subtree/a.txt")
        val b = lookup("/data/subtree/b.txt")
        val denied = lookup("/data/subtree/denied", FileType.DIRECTORY)
        connectionStreams(
            listOf(
                WalkEvent.Item(a),
                WalkEvent.DirError(denied, "listing denied"),
                WalkEvent.Item(b),
                WalkEvent.Done,
            )
        )
        val errors = mutableListOf<Pair<LocalPathLookup, Exception>>()

        runBlocking {
            walk(
                walkOptions = APathGateway.WalkOptions(
                    onError = { lookup, e ->
                        errors += lookup to e
                        true
                    },
                ),
            ) shouldContainExactly listOf(a, b)
        }

        errors.single().first shouldBe denied
        (errors.single().second is ReadException) shouldBe true
        errors.single().second.message shouldContain "listing denied"
    }

    @Test
    fun `DirError aborts the walk when onError returns false`() {
        val a = lookup("/data/subtree/a.txt")
        val denied = lookup("/data/subtree/denied", FileType.DIRECTORY)
        connectionStreams(
            listOf(
                WalkEvent.Item(a),
                WalkEvent.DirError(denied, "listing denied"),
                WalkEvent.Done,
            )
        )

        runBlocking {
            val thrown = shouldThrow<ReadException> {
                walk(walkOptions = APathGateway.WalkOptions(onError = { _, _ -> false }))
            }
            thrown.message shouldContain "listing denied"
        }
    }

    @Test
    fun `FatalError surfaces as ReadException with the host message`() {
        val a = lookup("/data/subtree/a.txt")
        connectionStreams(
            listOf(
                WalkEvent.Item(a),
                WalkEvent.FatalError(path, "host walk blew up"),
            )
        )

        runBlocking {
            val thrown = shouldThrow<ReadException> { walk() }
            thrown.message shouldContain "host walk blew up"
        }
    }

    @Test
    fun `a stream ending without a terminal event is reported as truncation`() {
        val a = lookup("/data/subtree/a.txt")
        connectionStreams(listOf(WalkEvent.Item(a)))

        runBlocking {
            val thrown = shouldThrow<ReadException> { walk() }
            thrown.message shouldContain "truncated"
        }
    }

    @Test
    fun `walk rejects options with a traversal filter`() {
        shouldThrow<IllegalArgumentException> {
            client.walk(
                path = path,
                lookupOptions = LookupOptions.BASE,
                walkOptions = APathGateway.WalkOptions(onFilter = { true }),
            )
        }
    }
}
