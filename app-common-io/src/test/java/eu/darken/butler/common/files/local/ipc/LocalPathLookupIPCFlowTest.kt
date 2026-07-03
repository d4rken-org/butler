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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
class LocalPathLookupIPCFlowTest : BaseTest() {

    private fun lookup(name: String) = LocalPathLookup(
        lookedUp = LocalPath.build(name),
        fileType = FileType.FILE,
        size = 0L,
        modifiedAt = null,
    )

    @Test
    fun `consumer going away does not fault the host scope`() {
        // Regression guard for #2506: when the client closes its end mid-stream (cancelled scan,
        // take()), the host writer's next pipe write fails with "Pipe closed". That failure must be
        // contained, not rethrown into `scope` — in production `scope` is the helper's unsupervised
        // app scope, where an uncaught exception kills the privileged process (surfacing as
        // ServiceConnectionLost on the next IPC call).
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(
            supervisor + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        try {
            runBlocking {
                val source: Flow<LocalPathLookup> = flow {
                    repeat(100_000) { emit(lookup("file$it")) }
                }
                val remote = source.toRemoteInputStream(scope)
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
        // The narrow containment must only swallow consumer-gone (ConsumerGone); a real source/
        // marshalling failure must still fault loudly so it isn't silently turned into a truncated
        // listing (Butler's stream has no terminal marker to detect truncation).
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(
            supervisor + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        try {
            runBlocking {
                val source: Flow<LocalPathLookup> = flow {
                    emit(lookup("file0"))
                    throw IllegalStateException("source blew up")
                }
                source.toRemoteInputStream(scope)

                withTimeout(10_000) { supervisor.children.toList().joinAll() }
                // Genuine error propagated to the scope's handler rather than being contained.
                uncaught.shouldNotBeEmpty()
                (uncaught.any { it is IllegalStateException }) shouldBe true
            }
        } finally {
            supervisor.cancel()
        }
    }
}
