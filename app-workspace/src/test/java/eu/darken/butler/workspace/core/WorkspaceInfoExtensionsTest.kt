package eu.darken.butler.workspace.core

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException

class WorkspaceInfoExtensionsTest : BaseTest() {

    private class FakePickerArguments(
        override val type: Workspace.Type,
        override val callerWorkspaceId: Workspace.Id?,
    ) : Workspace.ArgumentsForResult {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeArguments(
        override val type: Workspace.Type,
    ) : Workspace.Arguments {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeWorkspace(
        override val type: Workspace.Type = Workspace.Type.EXPLORER,
    ) : Workspace<Workspace.Arguments> {
        override val id = Workspace.Id()
        override val info = MutableStateFlow(Workspace.Info(id, type, "stub".toCaString()))
        override suspend fun createArguments(): Workspace.Arguments = FakeArguments(type)
    }

    @Test
    fun `initialInfo seeds static fields from caller arguments`() {
        val workspace = FakeWorkspace()
        val caller = Workspace.Id()

        val seed = workspace.initialInfo(
            title = "title".toCaString(),
            arguments = FakePickerArguments(Workspace.Type.EXPLORER, caller),
        )

        seed.id shouldBe workspace.id
        seed.type shouldBe workspace.type
        seed.callerWorkspaceId shouldBe caller
        seed.modalPresentation shouldBe Workspace.ModalPresentationMode.FULL_SCREEN
        seed.isSubWorkspace shouldBe true
    }

    @Test
    fun `initialInfo defaults for plain arguments`() {
        val workspace = FakeWorkspace()

        val seed = workspace.initialInfo(
            title = "title".toCaString(),
            arguments = FakeArguments(Workspace.Type.EXPLORER),
        )

        seed.callerWorkspaceId shouldBe null
        seed.modalPresentation shouldBe Workspace.ModalPresentationMode.PANE_LOCAL
        seed.isSubWorkspace shouldBe false
    }

    @Test
    fun `value is the seed before the first emission`() = runTest {
        val workspace = FakeWorkspace()
        val seed = workspace.initialInfo("seed".toCaString(), FakeArguments(workspace.type))

        // Upstream never emits — .value must still answer synchronously with the seed.
        val info = flow<Workspace.Info> { kotlinx.coroutines.awaitCancellation() }
            .stateInWorkspace(backgroundScope, seed)

        info.value shouldBe seed
    }

    @Test
    fun `upstream errors map to Error lifecycle state instead of killing the flow`() =
        runTest(UnconfinedTestDispatcher()) {
            val workspace = FakeWorkspace()
            val seed = workspace.initialInfo("seed".toCaString(), FakeArguments(workspace.type))

            val info = flow<Workspace.Info> { throw IOException("upstream broke") }
                .stateInWorkspace(backgroundScope, seed)

            val lifecycleState = info.value.lifecycleState
            lifecycleState.shouldBeInstanceOf<Workspace.LifecycleState.Error>()
            lifecycleState.error.shouldBeInstanceOf<IOException>()
            // Static fields survive the error mapping
            info.value.id shouldBe workspace.id
        }
}
