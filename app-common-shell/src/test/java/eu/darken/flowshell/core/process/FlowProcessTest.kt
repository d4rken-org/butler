package eu.darken.flowshell.core.process


import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.flow.replayingShare
import eu.darken.flowshell.core.FlowShellDebug
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

class FlowProcessTest : BaseTest() {

    private val READY_MARKER = "READY"
    private val WATCHDOG = 30.seconds

    @BeforeEach
    fun setup() {
        FlowShellDebug.isDebug = true
    }

    @AfterEach
    fun teardown() {
        FlowShellDebug.isDebug = false
    }

    @Test fun `base opening`() = runTest {
        var opened = false
        var killed = false
        val flow = FlowProcess(
            launch = {
                opened = true
                ProcessBuilder("sh", "-c", "echo 'Error' 1>&2; echo 'Input'; sleep 1").start()
            },
            kill = {
                killed = true
                it.destroyForcibly()
            }
        )

        flow.session.first()

        opened shouldBe true
        killed shouldBe true
    }

    @Test fun `session waits`() = runTest {
        var killedWhileAlive: Boolean? = null
        val flow = FlowProcess(
            launch = { gatedProcess() },
            kill = {
                killedWhileAlive = it.isAlive
                log { "Killing process" }
                it.destroyForcibly()
                log { "Process killed" }
            }
        )

        val sessionRef = CompletableDeferred<FlowProcess.Session>()
        val collector = launch(Dispatchers.IO) {
            flow.session.collect {
                sessionRef.complete(it)
                log { "Waiting for exit code" }
                it.waitFor() shouldBe FlowProcess.ExitCode.OK
            }
        }

        val session = awaitIo { sessionRef.await() }
        session.awaitReady()

        // The process blocks until released, so the flow is provably still waiting on it
        collector.isCompleted shouldBe false
        killedWhileAlive shouldBe null

        session.release()
        awaitIo { collector.join() }

        // The kill routine only ran after the process had exited on its own
        killedWhileAlive shouldBe false
    }

    @Test fun `session stays open`() = runTest {
        val flow = FlowProcess(
            launch = {
                ProcessBuilder("sh").start()
            },
        )
        var session: FlowProcess.Session? = null
        val ready = CompletableDeferred<Unit>()
        flow.session.onEach { session = it; ready.complete(Unit) }.launchIn(this)
        // Explicit signal instead of a delay
        ready.await()
        session shouldNotBe null

        val writer = session!!.input.buffered().bufferedWriter()

        (1..100).forEach {
            writer.write("echo hi$it\n")
            writer.flush()
        }

        session!!.isAlive() shouldBe true

        writer.write("exit\n")
        writer.flush()

        log { "Waiting for exit code" }
        session!!.waitFor() shouldBe FlowProcess.ExitCode.OK
    }

    @Test fun `wait for blocks until exit`() = runTest2(autoCancel = true) {
        val sharedSession = FlowProcess(
            launch = { gatedProcess() },
        ).session.replayingShare(this)
        sharedSession.launchIn(this + Dispatchers.IO)

        sharedSession.first().apply {
            awaitReady()

            val waiter = async(Dispatchers.IO) { waitFor() }

            // The process blocks until released, so waitFor() cannot have returned yet
            isAlive() shouldBe true
            waiter.isCompleted shouldBe false

            release()
            awaitIo { waiter.await() } shouldBe FlowProcess.ExitCode.OK
        }
    }

    @Test fun `session can be killed`() = runTest {
        val flow = FlowProcess(
            launch = { gatedProcess() },
        )

        flow.session.collect {
            it.awaitReady()
            it.cancel()
            // The process never exits on its own, so an exit code here can only come from the kill
            it.waitFor() shouldBe FlowProcess.ExitCode(137)
        }
    }

    @Test fun `session is killed on scope cancel`() = runTest {
        var killedWhileAlive: Boolean? = null

        val flow = FlowProcess(
            launch = {
                log { "Launching process" }
                gatedProcess().also { log { "Process launched" } }
            },
            kill = {
                log { "Killing process" }
                killedWhileAlive = it.isAlive
                it.destroyForcibly()
                log { "Process killed" }
            }
        )

        log { "Waiting for exit code" }
        // The process never exits on its own, so an exit code here can only come from the kill
        // that the closing scope triggers
        flow.session.first().exitCode.filterNotNull().first() shouldBe FlowProcess.ExitCode(137)

        killedWhileAlive shouldBe true
    }

    @Test fun `exception on close`() = runTest {
        val flow = FlowProcess(
            launch = {
                log { "Launching process" }
                ProcessBuilder("sleep", "1").start().also {
                    log { "Process launched" }
                }
            },
            kill = {
                log { "Killing process" }
                throw IOException("test")
            }
        )

        log { "Waiting for throw" }
        shouldThrow<IOException> {
            flow.session.first()
        }
        log { "We threw :)" }
    }

    @Test fun `exception on open`() = runTest {
        val flow = FlowProcess(
            launch = {
                throw IOException("test")
            },
        )

        log { "Waiting for throw" }
        shouldThrow<IOException> {
            flow.session.first()
        }
    }

    @Test fun `session is restartable`() = runTest {
        var startCount = 0
        val flow = FlowProcess(
            launch = {
                ProcessBuilder("echo", "<3").start().also {
                    startCount++
                }
            },
        )

        log { "Waiting for exit code (launch #1)" }
        flow.session.collect {
            it.waitFor() shouldBe FlowProcess.ExitCode.OK
        }
        startCount shouldBe 1

        log { "Waiting for exit code (launch #2)" }
        flow.session.collect {
            it.waitFor() shouldBe FlowProcess.ExitCode.OK
        }
        startCount shouldBe 2
    }

    @Test fun `session is kill and restartable`() = runTest {
        var startCount = 0
        val flow = FlowProcess(
            launch = {
                ProcessBuilder("sleep", "1").start().also {
                    startCount++
                }
            },
        )

        // Immediately ends the scope after the emission
        log { "Starting and killing (launch #1)" }
        flow.session.first().exitCode.first() shouldNotBe FlowProcess.ExitCode.OK
        startCount shouldBe 1

        log { "Waiting for exit code (launch #2)" }
        flow.session.collect {
            it.waitFor() shouldBe FlowProcess.ExitCode.OK
        }
        startCount shouldBe 2
    }

    /**
     * A process that announces itself and then blocks until the test releases it via stdin.
     * Lets a test prove a process is still running without asserting elapsed wall-clock time.
     */
    private fun gatedProcess(): Process = ProcessBuilder("sh", "-c", "echo $READY_MARKER; read _").start()

    /** Blocks until [gatedProcess] signalled that it is running. */
    private suspend fun FlowProcess.Session.awaitReady() = awaitIo {
        output.bufferedReader().readLine() shouldBe READY_MARKER
    }

    /** Lets a [gatedProcess] finish. */
    private suspend fun FlowProcess.Session.release() = awaitIo {
        input.write("go\n".toByteArray())
        input.flush()
    }

    /**
     * Runs [block] on a real dispatcher with a generous watchdog, so a hang fails instead of
     * blocking the worker. Never uses the test scheduler's virtual clock.
     */
    private suspend fun <T> awaitIo(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO) {
        withTimeout(WATCHDOG) { block() }
    }

    @Test fun `session is killed via pid`() = runTest {
        var opened = false
        var killed = false
        val flow = FlowProcess(
            launch = {
                opened = true
                ProcessBuilder("sh").start()
            },
            kill = {
                it.killViaPid()
                killed = true
            }
        )

        flow.session.first().apply {
            waitFor() shouldBe FlowProcess.ExitCode(137)
        }

        opened shouldBe true
        killed shouldBe true
    }
}