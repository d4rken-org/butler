package eu.darken.butler.workspace.core

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspacePauseGateTest : BaseTest() {

    private val idA = Workspace.Id()
    private val idB = Workspace.Id()

    @Test
    fun `the same workspace id runs one lease at a time`() = runTest(UnconfinedTestDispatcher()) {
        val gate = WorkspacePauseGate()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            gate.withLease(idA) {
                order += "first-enter"
                release.await()
                order += "first-exit"
            }
        }
        val second = launch {
            gate.withLease(idA) { order += "second" }
        }

        order shouldBe listOf("first-enter")

        release.complete(Unit)
        first.join()
        second.join()

        order shouldBe listOf("first-enter", "first-exit", "second")
    }

    @Test
    fun `a lease on one workspace does not block another`() = runTest(UnconfinedTestDispatcher()) {
        val gate = WorkspacePauseGate()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            gate.withLease(idA) {
                order += "a-enter"
                release.await()
                order += "a-exit"
            }
        }
        val second = launch {
            gate.withLease(idB) { order += "b" }
        }

        order shouldBe listOf("a-enter", "b")

        release.complete(Unit)
        first.join()
        second.join()
    }

    @Test
    fun `a failing lease is still released`() = runTest(UnconfinedTestDispatcher()) {
        val gate = WorkspacePauseGate()
        var ran = false

        try {
            gate.withLease(idA) { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }

        gate.withLease(idA) { ran = true }
        ran shouldBe true
    }
}
