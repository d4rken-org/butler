package eu.darken.flowshell.core.cmd


import eu.darken.butler.common.flow.replayingShare
import eu.darken.flowshell.core.FlowShellDebug
import eu.darken.flowshell.core.process.FlowProcess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
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
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2

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

    @Test fun `closing session drains an in-flight command`() = runTest2(autoCancel = true) {
        val sharedSession = FlowCmdShell().session.replayingShare(this)
        sharedSession.launchIn(this + Dispatchers.IO)
        val session = sharedSession.first()

        val cmd = async(Dispatchers.IO) {
            FlowCmd("sleep 1").execute(session)
        }

        // Real (non-virtual) delay so the command is actually running before we close.
        // runTest's virtual time would skip a plain delay() and race the close().
        withContext(Dispatchers.IO) { delay(500) }
        session.close()

        // close() is graceful: the already-submitted command is allowed to drain to
        // completion and returns normally. Use cancel() to abort a running command.
        cmd.await().exitCode shouldBe FlowProcess.ExitCode.OK
    }

    @Test fun `killing session aborts command`() = runTest2(autoCancel = true) {
        val sharedSession = FlowCmdShell().session.replayingShare(this)
        sharedSession.launchIn(this + Dispatchers.IO)
        val session = sharedSession.first()

        val cmdJob = launch(Dispatchers.IO) {
            shouldThrow<Exception> {
                FlowCmd("sleep 3").execute(session)
            }
        }

        // Real (non-virtual) delay so the command is actually running before we cancel.
        // runTest's virtual time would skip a plain delay() and race the cancel().
        withContext(Dispatchers.IO) { delay(500) }
        session.cancel()
        cmdJob.join()
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

    @Test fun `commands can be timeoutted`(): Unit = runBlocking {
        val start = System.currentTimeMillis()

        shouldThrow<TimeoutCancellationException> {
            withTimeout(1000) {
                FlowCmd("sleep 3", "echo done").execute().apply {
                    exitCode shouldBe FlowProcess.ExitCode.OK
                    output shouldBe listOf("done")
                }
            }
        }
        (System.currentTimeMillis() - start) shouldBeGreaterThanOrEqual 500
        (System.currentTimeMillis() - start) shouldBeLessThan 3000
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

    @Test fun `direct execution behavior`() = runTest {
        val start = System.currentTimeMillis()
        FlowCmd("sleep 1", "echo done").execute().apply {
            exitCode shouldBe FlowProcess.ExitCode.OK
            output shouldBe listOf("done")
        }
        (System.currentTimeMillis() - start) shouldBeGreaterThanOrEqual 1000
    }

    @Test fun `exec replacing shell blocks until replacement exits and returns -1 without throwing`(): Unit = runBlocking {
        // RootHostLauncher writes an `exec ...` line that swaps the shell with the host process,
        // so no idEnd marker is ever echoed. execute() must stay blocked until the replacement
        // exits (joinAll, woken by the death-watcher) and surface ExitCode(-1) instead of throwing.
        val start = System.currentTimeMillis()
        val result = FlowCmd("exec sleep 1").execute()
        val elapsed = System.currentTimeMillis() - start

        elapsed shouldBeGreaterThanOrEqual 900
        result.exitCode shouldBe FlowProcess.ExitCode(-1)
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