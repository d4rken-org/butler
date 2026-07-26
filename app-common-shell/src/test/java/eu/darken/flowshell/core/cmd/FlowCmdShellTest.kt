package eu.darken.flowshell.core.cmd


import eu.darken.butler.common.flow.replayingShare
import eu.darken.flowshell.core.ShellGate
import eu.darken.flowshell.core.FlowShellDebug
import eu.darken.flowshell.core.process.FlowProcess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import java.io.File

class FlowCmdShellTest : BaseTest() {
    @BeforeEach
    fun setup() {
        FlowShellDebug.isDebug = true
    }

    @AfterEach
    fun teardown() {
        FlowShellDebug.isDebug = false
    }

    @Test fun `base operation`() = runTest2(autoCancel = true) {
        val sharedSession = FlowCmdShell().session.replayingShare(this)
        sharedSession.launchIn(this + Dispatchers.IO)
        val session = sharedSession.first()
        session.isAlive() shouldBe true

        val cmd = FlowCmd(
            "echo output test",
            "echo error test >&2",
        )
        session.execute(cmd).apply {
            original shouldBe cmd
            exitCode shouldBe FlowProcess.ExitCode.OK
            output shouldBe listOf("output test")
            errors shouldBe listOf("error test")
        }

        session.close()
        session.isAlive() shouldBe false
    }

    @Test fun `quick execute`() = runTest {
        FlowCmd("echo 123").execute().apply {
            output shouldBe listOf("123")
            exitCode shouldBe FlowProcess.ExitCode.OK
        }
    }

    @Test fun `closing session drains an in-flight command`(@TempDir tempDir: File) = runTest2(autoCancel = true) {
        val sharedSession = FlowCmdShell().session.replayingShare(this)
        sharedSession.launchIn(this + Dispatchers.IO)
        val session = sharedSession.first()
        val gate = ShellGate(tempDir)

        val cmd = async(Dispatchers.IO) {
            FlowCmd(gate.instruction).execute(session)
        }

        // Explicit signal instead of a delay: the command is provably still running
        withContext(Dispatchers.IO) { gate.awaitStarted() }

        val closeEntered = CompletableDeferred<Unit>()
        val closing = async(Dispatchers.IO) {
            closeEntered.complete(Unit)
            session.close()
        }
        closeEntered.await()
        gate.release()

        // close() is graceful: the already-submitted command is allowed to drain to
        // completion and returns normally. Use cancel() to abort a running command.
        cmd.await().exitCode shouldBe FlowProcess.ExitCode.OK
        closing.await()
    }

    @Test fun `killing session aborts command`(@TempDir tempDir: File) = runTest2(autoCancel = true) {
        val sharedSession = FlowCmdShell().session.replayingShare(this)
        sharedSession.launchIn(this + Dispatchers.IO)
        val session = sharedSession.first()
        // Never released: the command can only end because the session was killed
        val gate = ShellGate(tempDir)

        val cmdJob = launch(Dispatchers.IO) {
            shouldThrow<Exception> {
                FlowCmd(gate.instruction).execute(session)
            }
        }

        // Explicit signal instead of a delay: the command is provably still running
        withContext(Dispatchers.IO) { gate.awaitStarted() }
        session.cancel()
        cmdJob.join()

        gate.wasReleased shouldBe false
    }

    @Test fun `queued commands`() = runTest2(autoCancel = true) {
        FlowCmdShell().session.collect { session ->
            session.counter shouldBe 0
            (1..1000).forEach {
                FlowCmd(
                    "echo output$it",
                    "echo error$it >&2",
                ).execute(session).apply {
                    exitCode shouldBe FlowProcess.ExitCode.OK
                    output shouldBe listOf("output$it")
                    errors shouldBe listOf("error$it")
                }
                session.counter shouldBe it
            }
            session.counter shouldBe 1000
            session.close()
        }
    }

    @Test fun `race command commands`() = runTest2(autoCancel = true) {
        FlowCmdShell().session.collect { session ->
            session.counter shouldBe 0
            (1..1000)
                .map {
                    launch(Dispatchers.IO) {
                        delay(5)
                        FlowCmd(
                            "echo output$it",
                            "echo error$it >&2",
                        ).execute(session).apply {
                            exitCode shouldBe FlowProcess.ExitCode.OK
                            output shouldBe listOf("output$it")
                            errors shouldBe listOf("error$it")
                        }
                    }
                }
                .toList()
                .joinAll()
            session.counter shouldBe 1000
            session.close()
        }
    }

    @Test fun `commands can be timeoutted`(@TempDir tempDir: File): Unit = runBlocking {
        // The command blocks until released, and it never is: only the timeout can end this.
        val gate = ShellGate(tempDir)

        // Throwing instead of returning a result proves execute() was still waiting on the
        // command, and returning at all proves the unwind did not wait for it to finish.
        shouldThrow<TimeoutCancellationException> {
            withTimeout(1000) {
                FlowCmd(gate.instruction, "echo done").execute().apply {
                    exitCode shouldBe FlowProcess.ExitCode.OK
                    output shouldBe listOf("done")
                }
            }
        }

        gate.wasReleased shouldBe false
    }

    @Test fun `open session extension`() = runTest2(autoCancel = true) {
        val (session, _) = FlowCmdShell().openSession(this)

        FlowCmd("echo done").execute(session).apply {
            exitCode shouldBe FlowProcess.ExitCode.OK
            output shouldBe listOf("done")
        }
    }

    @Test fun `cancellation behavior`() = runTest2(autoCancel = true) {
        val (session, _) = FlowCmdShell().openSession(this)

        shouldThrow<TimeoutCancellationException> {
            withTimeout(500) {
                FlowCmd("sleep 3", "echo nope").execute(session).apply {
                    exitCode shouldBe FlowProcess.ExitCode.OK
                }
            }
        }

        FlowCmd("echo done").execute(session).apply {
            exitCode shouldBe FlowProcess.ExitCode.OK
            output shouldBe listOf("done")
        }
    }

    @Test fun `direct execution waits for the command to finish`(@TempDir tempDir: File): Unit = runBlocking {
        val gate = ShellGate(tempDir)
        val execution = async(Dispatchers.IO) {
            FlowCmd(gate.instruction, "echo done").execute()
        }

        withContext(Dispatchers.IO) { gate.awaitStarted() }
        // The command is provably still running, so a result now would be a premature return
        execution.isCompleted shouldBe false

        gate.release()
        withTimeout(ShellGate.WATCHDOG) { execution.await() }.apply {
            exitCode shouldBe FlowProcess.ExitCode.OK
            output shouldBe listOf("done")
        }
    }

    @Test fun `exec replacing shell blocks until replacement exits and returns -1 without throwing`(
        @TempDir tempDir: File
    ): Unit = runBlocking {
        // RootHostLauncher writes an `exec ...` line that swaps the shell with the host process,
        // so no idEnd marker is ever echoed. execute() must stay blocked until the replacement
        // exits (joinAll, woken by the death-watcher) and surface ExitCode(-1) instead of throwing.
        val gate = ShellGate(tempDir)
        val execution = async(Dispatchers.IO) {
            FlowCmd("exec sh -c \"${gate.instruction}\"").execute()
        }

        withContext(Dispatchers.IO) { gate.awaitStarted() }
        // The replacement process is provably still running
        execution.isCompleted shouldBe false

        gate.release()
        withTimeout(ShellGate.WATCHDOG) { execution.await() }
            .exitCode shouldBe FlowProcess.ExitCode(-1)
    }

    @Test fun `quoted exec is not treated as shell replacement`(): Unit = runBlocking {
        // The shell does not actually exec here, so a normal idEnd marker is emitted and parsed;
        // the EXEC_REGEX heuristic must not hijack this into an ExitCode(-1).
        FlowCmd("echo 'exec foo'").execute().apply {
            exitCode shouldBe FlowProcess.ExitCode.OK
            output shouldBe listOf("exec foo")
        }
    }

    @Test fun `external shell death without exec throws`(): Unit = runBlocking {
        // The shell dies (kills itself) without an exec directive -> no marker, not an intentional
        // exec-replacement -> execute() must throw rather than masking the failure as ExitCode(-1).
        shouldThrow<IllegalStateException> {
            FlowCmd("kill -9 \$\$").execute()
        }
    }

}