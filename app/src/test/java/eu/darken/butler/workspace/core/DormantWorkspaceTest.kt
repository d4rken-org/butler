package eu.darken.butler.workspace.core

import android.os.Parcel
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DormantWorkspaceTest : BaseTest() {

    private class FakeArguments(
        override val type: Workspace.Type,
    ) : Workspace.Arguments {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakePickerArguments(
        override val type: Workspace.Type,
        override val callerWorkspaceId: Workspace.Id?,
    ) : Workspace.ArgumentsForResult {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeContentArguments(
        override val type: Workspace.Type,
        override val contentPath: APath<*>?,
    ) : Workspace.ArgumentsWithContentPath {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    @Test
    fun `starts dormant without an error`() {
        val id = Workspace.Id()
        val workspace = DormantWorkspace(
            id = id,
            type = Workspace.Type.EXPLORER,
            heldArguments = FakeArguments(Workspace.Type.EXPLORER),
        )

        workspace.info.value.id shouldBe id
        workspace.info.value.type shouldBe Workspace.Type.EXPLORER
        workspace.info.value.lifecycleState shouldBe Workspace.LifecycleState.Dormant()
        workspace.info.value.callerWorkspaceId shouldBe null
        workspace.info.value.contentPath shouldBe null
    }

    @Test
    fun `seeds the content path so open-dedup matches while dormant`() {
        val path = LocalPath.build("/test/a.txt")
        val workspace = DormantWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EDITOR,
            heldArguments = FakeContentArguments(Workspace.Type.EDITOR, path),
        )

        workspace.info.value.contentPath shouldBe path
    }

    @Test
    fun `seeds caller and presentation of sub-workspace arguments`() {
        val callerId = Workspace.Id()
        val workspace = DormantWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            heldArguments = FakePickerArguments(Workspace.Type.EXPLORER, callerId),
        )

        workspace.info.value.callerWorkspaceId shouldBe callerId
        workspace.info.value.isSubWorkspace shouldBe true
        workspace.info.value.modalPresentation shouldBe Workspace.ModalPresentationMode.FULL_SCREEN
    }

    @Test
    fun `passes the held arguments through unchanged`() = runTest {
        val arguments = FakeArguments(Workspace.Type.SEARCHER)
        val workspace = DormantWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.SEARCHER,
            heldArguments = arguments,
        )

        workspace.createArguments() shouldBe arguments
    }

    @Test
    fun `a hydration error is recorded without leaving the dormant state`() {
        val workspace = DormantWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            heldArguments = FakeArguments(Workspace.Type.EXPLORER),
        )
        val error = IllegalStateException("Factory exploded")

        workspace.markHydrationError(error)

        workspace.info.value.lifecycleState shouldBe Workspace.LifecycleState.Dormant(error)
    }
}
