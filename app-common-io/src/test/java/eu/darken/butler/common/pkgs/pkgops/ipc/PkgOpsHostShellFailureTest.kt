package eu.darken.butler.common.pkgs.pkgops.ipc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.shell.SharedShell
import eu.darken.flowshell.core.cmd.FlowCmd
import eu.darken.flowshell.core.cmd.FlowCmdShell
import eu.darken.flowshell.core.process.FlowProcess
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/**
 * `pm uninstall` and `pm clear` answer a refusal with a non-OK exit code and an explanation on
 * stdout/stderr. Returning false for that would lose both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PkgOpsHostShellFailureTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = mockk<FlowCmdShell.Session>()
    private val executed = slot<FlowCmd>()

    private fun createHost(result: FlowCmd.Result): PkgOpsHost {
        coEvery { session.execute(capture(executed)) } returns result
        val sharedShell = mockk<SharedShell>()
        coEvery { sharedShell.useRes<FlowCmd.Result>(any()) } coAnswers {
            firstArg<suspend (FlowCmdShell.Session) -> FlowCmd.Result>().invoke(session)
        }
        return PkgOpsHost(
            context = context,
            libcoreTool = mockk(relaxed = true),
            sharedShell = sharedShell,
            processScanner = mockk(relaxed = true),
        )
    }

    private fun result(
        exitCode: FlowProcess.ExitCode,
        output: List<String> = emptyList(),
        errors: List<String> = emptyList(),
    ) = FlowCmd.Result(
        original = FlowCmd("noop"),
        exitCode = exitCode,
        output = output,
        errors = errors,
    )

    @Test
    fun `a refused uninstall throws with the shell output`() {
        val host = createHost(
            result(
                exitCode = FlowProcess.ExitCode(1),
                output = listOf("Failure [DELETE_FAILED_INTERNAL_ERROR]"),
                errors = listOf("Exception occurred while executing"),
            )
        )

        val thrown = shouldThrow<Exception> { host.uninstallPackage("com.example.app", 0) }

        thrown.message!! shouldContain "Failure [DELETE_FAILED_INTERNAL_ERROR]"
        thrown.message!! shouldContain "Exception occurred while executing"
        executed.captured.instructions shouldBe listOf("pm uninstall --user 0 com.example.app")
    }

    @Test
    fun `an accepted uninstall reports success`() {
        val host = createHost(result(exitCode = FlowProcess.ExitCode.OK, output = listOf("Success")))

        host.uninstallPackage("com.example.app", 0) shouldBe true
    }

    @Test
    fun `a refused clear data throws with the shell output`() {
        val host = createHost(
            result(
                exitCode = FlowProcess.ExitCode(255),
                output = listOf("Failed"),
                errors = listOf("Permission Denial"),
            )
        )

        val thrown = shouldThrow<Exception> { host.clearData("com.example.app", 10) }

        thrown.message!! shouldContain "Failed"
        thrown.message!! shouldContain "Permission Denial"
        executed.captured.instructions shouldBe listOf("pm clear --user 10 com.example.app")
    }

    @Test
    fun `an accepted clear data reports success`() {
        val host = createHost(result(exitCode = FlowProcess.ExitCode.OK, output = listOf("Success")))

        host.clearData("com.example.app", 0) shouldBe true
    }
}
