package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.ipc.IpcErrorCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.coEvery
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
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException

/**
 * Client-side mapping of the walkStreamV2 event protocol in [FileOpsClient.walk]: Item emission,
 * DirError-to-onError routing, FatalError propagation, decoding of the error carriers and
 * truncation detection. The connection is mocked with a real [toEventRemoteStream] pipe, so the
 * full chunked wire format is exercised, and the host round trip drives a [FileOpsHost] over a
 * mocked backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class FileOpsClientWalkTest : BaseTest() {

    private val path = LocalPath.build("/data/subtree")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = mockk<FileOpsConnection>()
    private val client = FileOpsClient(connection)
    private val hostOps = mockk<LocalFileSystemOps>()

    private fun host() = FileOpsHost(
        appScope = scope,
        dispatcherProvider = TestDispatcherProvider(Dispatchers.IO),
        fileSystemOps = hostOps,
    )

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

    private suspend fun walkCollectingErrors(errors: MutableList<Exception>): List<LocalPathLookup> = walk(
        walkOptions = APathGateway.WalkOptions(
            onError = { _, e ->
                errors += e
                true
            },
        ),
    )

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
    fun `an encoded DirError reaches onError as the host type`() {
        val denied = lookup("/data/subtree/denied", FileType.DIRECTORY)
        val hostError = PathPermissionDeniedException(
            path = denied.lookedUp,
            operation = "lookupFiles",
            reason = PathPermissionDeniedException.Reason.NOT_PERMITTED,
        )
        connectionStreams(
            listOf(
                WalkEvent.DirError(denied, IpcErrorCodec.encodeCompact(hostError)),
                WalkEvent.Done,
            )
        )
        val errors = mutableListOf<Exception>()

        runBlocking { walkCollectingErrors(errors).shouldBeEmpty() }

        errors.single().shouldBeTypeOf<PathPermissionDeniedException>().apply {
            path!!.path shouldBe denied.lookedUp.path
            operation shouldBe "lookupFiles"
            reason shouldBe PathPermissionDeniedException.Reason.NOT_PERMITTED
        }
    }

    @Test
    fun `an encoded FatalError keeps its cause chain and the host frames`() {
        val hostError = ReadException(
            message = "Can't read from path.",
            path = path,
            cause = IOException("ENOENT (No such file or directory)"),
        ).apply {
            stackTrace = arrayOf(StackTraceElement("com.host.Walker", "list", "Walker.kt", 42))
        }
        connectionStreams(listOf(WalkEvent.FatalError(path, IpcErrorCodec.encode(hostError))))

        runBlocking {
            val thrown = shouldThrow<ReadException> { walk() }
            thrown.cause!!.message!! shouldContain "ENOENT"
            thrown.stackTrace.any { it.className == "com.host.Walker" } shouldBe true
        }
    }

    @Test
    fun `a markerless DirError message survives verbatim`() {
        val denied = lookup("/data/subtree/denied", FileType.DIRECTORY)
        connectionStreams(
            listOf(
                WalkEvent.DirError(denied, "EACCES (Permission denied)"),
                WalkEvent.Done,
            )
        )
        val errors = mutableListOf<Exception>()

        runBlocking { walkCollectingErrors(errors).shouldBeEmpty() }

        val message = errors.single().shouldBeTypeOf<ReadException>().message!!
        message shouldContain "EACCES (Permission denied)"
        message shouldNotContain "Undecodable"
    }

    @Test
    fun `a full chunk of encoded directory errors crosses the wire`() {
        val deep = lookup(
            "/data/subtree/" + (0 until 20).joinToString("/") { "level$it" },
            FileType.DIRECTORY,
        )
        val hostError = ReadException(
            message = "Can't read from path.",
            path = deep.lookedUp,
            cause = IOException("EACCES (Permission denied)"),
        ).apply {
            stackTrace = Array(100) { StackTraceElement("com.host.Walker", "list$it", "Walker.kt", it) }
        }
        val carrier = IpcErrorCodec.encodeCompact(hostError)
        carrier.toByteArray().size shouldBeLessThan 2048

        connectionStreams(List(100) { WalkEvent.DirError(deep, carrier) } + WalkEvent.Done)
        val errors = mutableListOf<Exception>()

        runBlocking { walkCollectingErrors(errors).shouldBeEmpty() }

        errors.size shouldBe 100
        errors.all { it is ReadException } shouldBe true
    }

    @Test
    fun `a host side listing denial reaches onError as its own type`() {
        val start = lookup(path.path, FileType.DIRECTORY)
        val hostError = PathPermissionDeniedException(
            path = path,
            operation = "lookupFiles",
            reason = PathPermissionDeniedException.Reason.NOT_PERMITTED,
        )
        coEvery { hostOps.lookup(path, LookupOptions.BASE) } returns start
        coEvery { hostOps.lookupFiles(path, LookupOptions.BASE) } throws hostError
        every { connection.walkStreamV2(any(), any(), any()) } answers {
            host().walkStreamV2(path, LookupOptions.BASE, WalkSpec())
        }
        val errors = mutableListOf<Exception>()

        runBlocking { walkCollectingErrors(errors).shouldBeEmpty() }

        errors.single().shouldBeTypeOf<PathPermissionDeniedException>().apply {
            path!!.path shouldBe hostError.path!!.path
            operation shouldBe "lookupFiles"
            reason shouldBe PathPermissionDeniedException.Reason.NOT_PERMITTED
        }
    }

    @Test
    fun `a host side walk failure crosses with its cause chain and the host frames`() {
        val hostError = PathPermissionDeniedException(
            path = path,
            operation = "lookup",
            reason = PathPermissionDeniedException.Reason.NOT_PERMITTED,
            cause = IOException("EACCES (Permission denied)"),
        ).apply {
            stackTrace = arrayOf(StackTraceElement("com.host.Walker", "lookupStart", "Walker.kt", 42))
        }
        coEvery { hostOps.lookup(path, LookupOptions.BASE) } throws hostError
        every { connection.walkStreamV2(any(), any(), any()) } answers {
            host().walkStreamV2(path, LookupOptions.BASE, WalkSpec())
        }

        runBlocking {
            val thrown = shouldThrow<PathPermissionDeniedException> { walk() }
            thrown.path!!.path shouldBe path.path
            thrown.operation shouldBe "lookup"
            thrown.reason shouldBe PathPermissionDeniedException.Reason.NOT_PERMITTED
            thrown.cause!!.message!! shouldContain "EACCES (Permission denied)"
            thrown.stackTrace.any { it.className == "com.host.Walker" } shouldBe true
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
