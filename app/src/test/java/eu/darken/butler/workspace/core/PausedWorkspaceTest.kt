package eu.darken.butler.workspace.core

import android.content.Context
import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PausedWorkspaceTest : BaseTest() {

    // Never touched: the titles under test are direct strings, not resource lookups
    private val context: Context = mockk()

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
    fun `starts paused without an error`() {
        val id = Workspace.Id()
        val workspace = PausedWorkspace(
            id = id,
            type = Workspace.Type.EXPLORER,
            heldArguments = FakeArguments(Workspace.Type.EXPLORER),
            title = "Home".toCaString(),
        )

        workspace.info.value.id shouldBe id
        workspace.info.value.type shouldBe Workspace.Type.EXPLORER
        workspace.info.value.lifecycleState shouldBe Workspace.LifecycleState.Paused()
        workspace.info.value.callerWorkspaceId shouldBe null
        workspace.info.value.contentPath shouldBe null
    }

    @Test
    fun `seeds the content path so open-dedup matches while paused`() {
        val path = LocalPath.build("/test/a.txt")
        val workspace = PausedWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EDITOR,
            heldArguments = FakeContentArguments(Workspace.Type.EDITOR, path),
            title = "a.txt".toCaString(),
        )

        workspace.info.value.contentPath shouldBe path
    }

    @Test
    fun `seeds caller and presentation of sub-workspace arguments`() {
        val callerId = Workspace.Id()
        val workspace = PausedWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            heldArguments = FakePickerArguments(Workspace.Type.EXPLORER, callerId),
            title = "Picker".toCaString(),
        )

        workspace.info.value.callerWorkspaceId shouldBe callerId
        workspace.info.value.isSubWorkspace shouldBe true
        workspace.info.value.modalPresentation shouldBe Workspace.ModalPresentationMode.FULL_SCREEN
    }

    @Test
    fun `passes the held arguments through unchanged`() = runTest {
        val arguments = FakeArguments(Workspace.Type.SEARCHER)
        val workspace = PausedWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.SEARCHER,
            heldArguments = arguments,
            title = "*.pdf".toCaString(),
        )

        workspace.createArguments() shouldBe arguments
    }

    @Test
    fun `the derived identity lands in the info`() {
        val workspace = PausedWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.SEARCHER,
            heldArguments = FakeArguments(Workspace.Type.SEARCHER),
            title = "*.pdf".toCaString(),
            subtitle = "/sdcard/Download".toCaString(),
        )

        workspace.info.value.title.get(context) shouldBe "*.pdf"
        workspace.info.value.subtitle!!.get(context) shouldBe "/sdcard/Download"
    }

    @Test
    fun `an identity without a subtitle publishes none`() {
        val workspace = PausedWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            heldArguments = FakeArguments(Workspace.Type.EXPLORER),
            title = "Home".toCaString(),
        )

        workspace.info.value.title.get(context) shouldBe "Home"
        workspace.info.value.subtitle shouldBe null
    }

    @Test
    fun `a resume error keeps the identity`() {
        val workspace = PausedWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            heldArguments = FakeArguments(Workspace.Type.EXPLORER),
            title = "Home".toCaString(),
            subtitle = "Storage".toCaString(),
        )

        workspace.markResumeError(IllegalStateException("Factory exploded"))

        workspace.info.value.title.get(context) shouldBe "Home"
        workspace.info.value.subtitle!!.get(context) shouldBe "Storage"
    }

    @Test
    fun `a resume error is recorded without leaving the paused state`() {
        val workspace = PausedWorkspace(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            heldArguments = FakeArguments(Workspace.Type.EXPLORER),
            title = "Home".toCaString(),
        )
        val error = IllegalStateException("Factory exploded")

        workspace.markResumeError(error)

        workspace.info.value.lifecycleState shouldBe Workspace.LifecycleState.Paused(error)
    }
}
