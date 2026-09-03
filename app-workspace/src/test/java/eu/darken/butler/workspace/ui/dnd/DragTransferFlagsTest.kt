package eu.darken.butler.workspace.ui.dnd

import android.view.View
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

// Robolectric: DragAndDropTransferData wraps a ClipData, which needs the Android runtime.
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class DragTransferFlagsTest : BaseTest() {

    @Test
    fun `the transfer asks for an opaque drag shadow`() {
        val payload = WorkspaceDragPayload(
            sourceWorkspaceId = Workspace.Id(),
            items = listOf(
                WorkspaceDragPayload.Item(
                    path = LocalPath.build("/storage/emulated/0/one.txt"),
                    kind = WorkspaceDragPayload.Kind.FILE_OTHER,
                ),
            ),
            allowMove = true,
        )

        (payload.toTransferData().flags and View.DRAG_FLAG_OPAQUE) shouldBe View.DRAG_FLAG_OPAQUE
    }
}
