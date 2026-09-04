package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.ipc.inputStream
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class WalkEventIPCFlowTest : BaseTest() {

    private fun lookup(name: String) = LocalPathLookup(
        lookedUp = LocalPath.build(name),
        fileType = FileType.FILE,
        size = 0L,
        modifiedAt = null,
    )

    @Test
    fun `all event types round-trip across chunk boundaries`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                // More than one chunk (CHUNK_COUNT=100) so multi-chunk framing is exercised too
                val events = buildList {
                    repeat(250) { add(WalkEvent.Item(lookup("/walked/file$it"))) }
                    add(WalkEvent.DirError(lookup("/walked/denied"), "listing denied"))
                    add(WalkEvent.DirError(lookup("/walked/quiet"), null))
                    add(WalkEvent.FatalError(LocalPath.build("/walked"), "walk blew up"))
                    add(WalkEvent.FatalError(null, null))
                    add(WalkEvent.Done)
                }

                val remote = events.asFlow().toEventRemoteStream(scope)
                val received = withTimeout(10_000) { remote.toWalkEventFlow().toList() }

                received shouldBe events
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `consumer going away does not fault the host scope`() {
        // When the client closes its end mid-stream, the host writer's broken-pipe failure must not
        // fault the host's app scope (an uncaught exception there kills the privileged process).
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(
            supervisor + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        try {
            runBlocking {
                val source: Flow<WalkEvent> = flow {
                    repeat(100_000) { emit(WalkEvent.Item(lookup("/walked/file$it"))) }
                    emit(WalkEvent.Done)
                }
                val remote = source.toEventRemoteStream(scope)
                val stream = remote.inputStream()

                // Consume a little, then the consumer goes away and closes its end.
                stream.read()
                stream.close()

                // The writer must unwind on the broken pipe instead of hanging or faulting the scope.
                withTimeout(10_000) { supervisor.children.toList().joinAll() }
                uncaught shouldBe emptyList<Throwable>()
            }
        } finally {
            supervisor.cancel()
        }
    }

    @Test
    fun `a genuine upstream failure is still surfaced, not swallowed`() {
        // Only consumer-gone is contained; a real source/marshalling failure must fault loudly so
        // the host-side walk producer doesn't silently degrade into a truncated stream.
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(
            supervisor + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        try {
            runBlocking {
                val source: Flow<WalkEvent> = flow {
                    emit(WalkEvent.Item(lookup("/walked/file0")))
                    throw IllegalStateException("source blew up")
                }
                source.toEventRemoteStream(scope)

                withTimeout(10_000) { supervisor.children.toList().joinAll() }
                uncaught.shouldNotBeEmpty()
                (uncaught.any { it is IllegalStateException }) shouldBe true
            }
        } finally {
            supervisor.cancel()
        }
    }
}
