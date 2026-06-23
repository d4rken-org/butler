package eu.darken.butler.workspace.core.operations

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class OperationFocusRequestTest : BaseTest() {

    @Test
    fun `request publishes and consume clears`() {
        val holder = OperationFocusRequest()
        val ws = Workspace.Id()
        val op = Operation.Id()

        holder.requests.value shouldBe null

        holder.request(ws, op)
        val current = holder.requests.value
        current shouldNotBe null
        current!!.workspaceId shouldBe ws
        current.operationId shouldBe op

        holder.consume(current)
        holder.requests.value shouldBe null
    }

    @Test
    fun `consuming a stale request does not clobber a newer one`() {
        val holder = OperationFocusRequest()
        val ws = Workspace.Id()

        holder.request(ws, Operation.Id())
        val stale = holder.requests.value!!

        holder.request(ws, Operation.Id())
        val newer = holder.requests.value!!

        // Consuming the stale request must be a no-op since a newer one superseded it.
        holder.consume(stale)
        holder.requests.value shouldBe newer
    }
}
