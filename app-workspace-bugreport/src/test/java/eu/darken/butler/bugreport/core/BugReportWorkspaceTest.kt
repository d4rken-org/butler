package eu.darken.butler.bugreport.core

import eu.darken.butler.workspace.contracts.bugreport.BugReportArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class BugReportWorkspaceTest : BaseTest() {

    private fun create() = BugReportWorkspace(
        id = Workspace.Id(),
        creationArguments = BugReportArguments.Default(),
        dispatcherProvider = TestDispatcherProvider(),
    )

    @Test
    fun `exposes the BUG_REPORT type`() {
        val workspace = create()
        workspace.type shouldBe Workspace.Type.BUG_REPORT
        workspace.info.value.type shouldBe Workspace.Type.BUG_REPORT
    }

    @Test
    fun `createArguments returns the default singleton arguments`() = runBlocking {
        val workspace = create()
        workspace.createArguments() shouldBe BugReportArguments.Default()
        workspace.release()
    }
}
