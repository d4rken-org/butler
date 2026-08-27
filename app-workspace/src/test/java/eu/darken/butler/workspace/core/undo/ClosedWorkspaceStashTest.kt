package eu.darken.butler.workspace.core.undo

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.seconds

/**
 * The gate that decides when a closed tab may be offered back, and the rules that drop it again.
 *
 * The three conditions are set independently on purpose: the completion acknowledgement can arrive
 * before the identity half commits, so an implementation that waited for them in order would
 * deadlock on its own ordering. Both orders are asserted here.
 */
class ClosedWorkspaceStashTest : BaseTest() {

    private class FakeArguments(
        override val type: Workspace.Type,
    ) : Workspace.Arguments {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private lateinit var scope: TestScope
    private lateinit var stash: ClosedWorkspaceStash

    @BeforeEach
    fun setup() {
        scope = TestScope(UnconfinedTestDispatcher())
        stash = ClosedWorkspaceStash(scope)
    }

    private fun snapshotOf(closeToken: Long, id: Workspace.Id) = ClosedWorkspaceSnapshot(
        members = listOf(
            ClosedWorkspaceMember(
                id = id,
                type = Workspace.Type.EXPLORER,
                arguments = FakeArguments(Workspace.Type.EXPLORER),
                createdAt = null,
                customTitle = null,
                automaticTitle = "Tab".toCaString(),
                automaticSubtitle = null,
                callerWorkspaceId = null,
            )
        ),
        unitOrderIndex = 0,
        precedingNeighbourIds = emptyList(),
        followingNeighbourIds = emptyList(),
        closeToken = closeToken,
        baselineContentHolders = emptyMap(),
        baselineSingletonOccupants = null,
    )

    private fun armed(id: Workspace.Id): Long {
        val token = stash.nextToken()
        stash.armClose(token, id, setOf(id))
        return token
    }

    private fun contributeUiHalf(token: Long, id: Workspace.Id) {
        stash.contributeSlots(token, id, ClosedWorkspaceMemberSlots())
        stash.capturePlacement(token, ClosedWorkspacePlacement(paneIndex = 0, focusedMemberId = id))
    }

    @Test
    fun `nothing is offered until all three conditions hold`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)

        contributeUiHalf(token, id)
        stash.feedback.value shouldBe null

        stash.commitIdentity(snapshotOf(token, id))
        // Both halves are in, but the close's own teardown has not acknowledged completion yet
        stash.feedback.value shouldBe null
        stash.peekEntry() shouldBe null

        stash.markDestructionComplete(token)

        stash.feedback.value shouldNotBe null
        stash.peekEntry() shouldNotBe null
    }

    @Test
    fun `the acknowledgement may arrive before the identity half`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)

        contributeUiHalf(token, id)
        stash.markDestructionComplete(token)
        stash.feedback.value shouldBe null

        stash.commitIdentity(snapshotOf(token, id))

        stash.feedback.value shouldNotBe null
    }

    @Test
    fun `the close's own removals do not drop its entry`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)

        // The close's own recursion publishes a changed id set, once per member, under its token
        stash.onWorkspaceIdSetChanged(token)
        stash.onWorkspaceIdSetChanged(token)
        contributeUiHalf(token, id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.disarm(token)
        stash.markDestructionComplete(token)

        stash.feedback.value shouldNotBe null
    }

    @Test
    fun `an unrelated change while the close is in flight drops the entry`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)

        // The capture window runs without the repo lock, so somebody else can publish inside it.
        // Being armed does not make that mutation the close's own.
        stash.onWorkspaceIdSetChanged()

        contributeUiHalf(token, id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.disarm(token)
        stash.markDestructionComplete(token)

        stash.feedback.value shouldBe null
        stash.peekEntry() shouldBe null
    }

    @Test
    fun `a workspace change after the close drops the entry`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)
        contributeUiHalf(token, id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.disarm(token)
        stash.markDestructionComplete(token)

        stash.onWorkspaceIdSetChanged()

        stash.feedback.value shouldBe null
        stash.peekEntry() shouldBe null
        stash.peekStashedArguments() shouldBe emptyList()
    }

    @Test
    fun `a restore's own publication does not drop what it is restoring`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)
        contributeUiHalf(token, id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.disarm(token)
        stash.markDestructionComplete(token)

        stash.beginRestore()
        stash.onWorkspaceIdSetChanged()
        val entry = stash.peekEntry()
        stash.consume(token)
        stash.endRestore()

        entry shouldNotBe null
        stash.feedback.value shouldBe null
    }

    @Test
    fun `a newer close supersedes the previous entry`() = runTest {
        val first = Workspace.Id()
        val firstToken = armed(first)
        contributeUiHalf(firstToken, first)
        stash.commitIdentity(snapshotOf(firstToken, first))
        stash.disarm(firstToken)
        stash.markDestructionComplete(firstToken)
        stash.feedback.value shouldNotBe null

        val second = Workspace.Id()
        val secondToken = armed(second)

        stash.feedback.value shouldBe null
        stash.commitIdentity(snapshotOf(secondToken, second))
        contributeUiHalf(secondToken, second)
        stash.markDestructionComplete(secondToken)
        stash.feedback.value?.closeToken shouldBe secondToken
    }

    @Test
    fun `an older close still in flight does not drop the entry that superseded it`() = runTest {
        val first = Workspace.Id()
        val firstToken = armed(first)

        // A second close arms while the first one is still capturing, and finishes inside that window
        val second = Workspace.Id()
        val secondToken = armed(second)
        contributeUiHalf(secondToken, second)
        stash.commitIdentity(snapshotOf(secondToken, second))
        stash.disarm(secondToken)
        stash.markDestructionComplete(secondToken)
        stash.feedback.value?.closeToken shouldBe secondToken

        // The first close now runs its own removals, under its own token
        stash.onWorkspaceIdSetChanged(firstToken)
        stash.commitIdentity(snapshotOf(firstToken, first))
        stash.disarm(firstToken)
        stash.markDestructionComplete(firstToken)

        stash.feedback.value?.closeToken shouldBe secondToken
        stash.peekEntry() shouldNotBe null
    }

    @Test
    fun `a finished close's token stops being exempt`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)
        contributeUiHalf(token, id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.disarm(token)
        stash.markDestructionComplete(token)

        // Nothing is in flight under this token any more, so it is no longer a licence to mutate
        stash.onWorkspaceIdSetChanged(token)

        stash.feedback.value shouldBe null
        stash.peekEntry() shouldBe null
    }

    @Test
    fun `a close abandoned before it committed stops being exempt`() = runTest {
        val abandoned = Workspace.Id()
        val abandonedToken = armed(abandoned)
        // Cancelled after its capture and before it could commit anything, giving its token back
        stash.disarm(abandonedToken)

        // A later close supersedes the abandoned one's assembly and completes
        val id = Workspace.Id()
        val token = armed(id)
        contributeUiHalf(token, id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.disarm(token)
        stash.markDestructionComplete(token)
        stash.feedback.value?.closeToken shouldBe token

        // Superseding an assembly deliberately leaves its close armed, but this one was disarmed on
        // the way out, so a publication carrying its token is an ordinary mutation again
        stash.onWorkspaceIdSetChanged(abandonedToken)

        stash.feedback.value shouldBe null
        stash.peekEntry() shouldBe null
    }

    @Test
    fun `an identity half whose UI half never lands is dropped unoffered`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.markDestructionComplete(token)

        scope.testScheduler.advanceTimeBy(ClosedWorkspaceStash.ASSEMBLY_TIMEOUT + 1.seconds)
        scope.testScheduler.runCurrent()

        stash.feedback.value shouldBe null
        stash.peekEntry() shouldBe null
    }

    @Test
    fun `the offer expires`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)
        contributeUiHalf(token, id)
        stash.commitIdentity(snapshotOf(token, id))
        stash.markDestructionComplete(token)
        stash.feedback.value shouldNotBe null

        scope.testScheduler.advanceTimeBy(ClosedWorkspaceStash.FEEDBACK_TIMEOUT + 1.seconds)
        scope.testScheduler.runCurrent()

        stash.feedback.value shouldBe null
    }

    @Test
    fun `a stashed entry names what it holds`() = runTest {
        val id = Workspace.Id()
        val token = armed(id)
        stash.commitIdentity(snapshotOf(token, id))

        // Asked while the entry is still assembling too: the window between the close and the undo
        // is exactly when a resource would look unreachable.
        stash.peekStashedArguments().map { it.type } shouldBe listOf(Workspace.Type.EXPLORER)
    }

    @Test
    fun `an incarnation tells a restored id from a closed one`() = runTest {
        val id = Workspace.Id()
        stash.currentTokenOf(id) shouldBe null

        val first = stash.stampIncarnation(id)
        stash.currentTokenOf(id) shouldBe first

        stash.dropIncarnation(id)
        stash.currentTokenOf(id) shouldBe null

        val second = stash.stampIncarnation(id)
        (second > first) shouldBe true
    }

    @Test
    fun `a ticket is handed out once and never to a different incarnation`() = runTest {
        val id = Workspace.Id()
        val token = stash.stampIncarnation(id)
        val ticket = ClosedWorkspaceRestoreTicket(
            rootId = id,
            restoreToken = token,
            slots = emptyMap(),
            placement = ClosedWorkspacePlacement(paneIndex = 0, focusedMemberId = id),
        )
        stash.armRestoreTicket(ticket)

        stash.takeRestoreTicket(id, token) shouldBe ticket
        stash.takeRestoreTicket(id, token) shouldBe null

        // A same-id replacement stamps a new incarnation, which the old ticket must not apply to
        stash.armRestoreTicket(ticket)
        stash.stampIncarnation(id)
        stash.takeRestoreTicket(id, null) shouldBe null
    }
}
