package eu.darken.butler.history.core

import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class HistoryWorkspaceFocusTest : BaseTest() {

    private fun create(arguments: HistoryArguments) = HistoryWorkspace(
        id = Workspace.Id(),
        creationArguments = arguments,
        dispatcherProvider = TestDispatcherProvider(),
    )

    @Test
    fun `the focus entry stays pending until its entry was shown`() {
        val workspace = create(HistoryArguments.Default(focusEntryId = "op-1"))

        workspace.focusEntryId.value shouldBe "op-1"
        workspace.clearFocusEntryId("op-1")
        workspace.focusEntryId.value shouldBe null
    }

    @Test
    fun `showing a different entry leaves the pending focus alone`() {
        val workspace = create(HistoryArguments.Default(focusEntryId = "op-1"))

        workspace.clearFocusEntryId("op-2")

        workspace.focusEntryId.value shouldBe "op-1"
    }

    @Test
    fun `a restored tab does not reopen the entry it was created on`() = runTest {
        val workspace = create(HistoryArguments.Default(focusEntryId = "op-1"))

        workspace.createArguments().focusEntryId shouldBe null
    }
}
