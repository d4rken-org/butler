package eu.darken.butler.workspace.ui.dnd

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DropValidationTest : BaseTest() {

    private fun dir(path: String) = WorkspaceDragPayload.Item(
        path = LocalPath.build(path),
        kind = WorkspaceDragPayload.Kind.DIRECTORY,
    )

    private fun file(path: String) = WorkspaceDragPayload.Item(
        path = LocalPath.build(path),
        kind = WorkspaceDragPayload.Kind.FILE_OTHER,
    )

    private fun validate(vararg items: WorkspaceDragPayload.Item, destination: APath<*>) =
        validateDropPaths(items.toList(), destination)

    @Test
    fun `a plain cross directory drop is allowed`() {
        validate(
            file("/storage/emulated/0/DCIM/photo.jpg"),
            dir("/storage/emulated/0/DCIM/raw"),
            destination = LocalPath.build("/storage/emulated/0/Download"),
        ) shouldBe true
    }

    @Test
    fun `an empty payload is rejected`() {
        validateDropPaths(emptyList(), LocalPath.build("/storage/emulated/0/Download")) shouldBe false
    }

    @Test
    fun `dropping onto the dragged item itself is rejected`() {
        validate(
            dir("/storage/emulated/0/DCIM"),
            destination = LocalPath.build("/storage/emulated/0/DCIM"),
        ) shouldBe false
    }

    @Test
    fun `dropping into the direct parent is rejected`() {
        validate(
            file("/storage/emulated/0/DCIM/photo.jpg"),
            destination = LocalPath.build("/storage/emulated/0/DCIM"),
        ) shouldBe false
    }

    @Test
    fun `dropping a directory into its own subtree is rejected`() {
        validate(
            dir("/storage/emulated/0/DCIM"),
            destination = LocalPath.build("/storage/emulated/0/DCIM/raw/2024"),
        ) shouldBe false
    }

    @Test
    fun `a single offending item rejects a mixed payload`() {
        validate(
            file("/storage/emulated/0/Download/blob.bin"),
            file("/storage/emulated/0/DCIM/photo.jpg"),
            destination = LocalPath.build("/storage/emulated/0/DCIM"),
        ) shouldBe false
    }

    @Test
    fun `a file item does not block drops below its path`() {
        validate(
            file("/storage/emulated/0/DCIM/photo.jpg"),
            destination = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg/nested"),
        ) shouldBe true
    }

    @Test
    fun `segment prefixes that are not path prefixes are allowed`() {
        validate(
            dir("/storage/emulated/0/DCIMx"),
            destination = LocalPath.build("/storage/emulated/0/DCIM"),
        ) shouldBe true

        validate(
            dir("/storage/emulated/0/DCIM"),
            destination = LocalPath.build("/storage/emulated/0/DCIMx"),
        ) shouldBe true
    }

    @Test
    fun `an alias spelled item is still recognized as the drop target itself`() {
        validate(
            dir("/sdcard/DCIM"),
            destination = LocalPath.build("/storage/emulated/0/DCIM"),
        ) shouldBe false
    }

    @Test
    fun `an alias spelled parent still blocks the drop`() {
        validate(
            file("/sdcard/DCIM/photo.jpg"),
            destination = LocalPath.build("/storage/emulated/0/DCIM"),
        ) shouldBe false

        validate(
            file("/storage/emulated/0/DCIM/photo.jpg"),
            destination = LocalPath.build("/sdcard/DCIM"),
        ) shouldBe false
    }

    @Test
    fun `an alias spelled directory still blocks drops into its own subtree`() {
        validate(
            dir("/sdcard/DCIM"),
            destination = LocalPath.build("/storage/emulated/0/DCIM/raw/2024"),
        ) shouldBe false

        validate(
            dir("/storage/emulated/0/DCIM"),
            destination = LocalPath.build("/sdcard/DCIM/raw"),
        ) shouldBe false
    }

    @Test
    fun `dot segments cannot hide a self drop`() {
        validate(
            dir("/storage/emulated/0/DCIM"),
            destination = LocalPath.build("/storage/emulated/0/Download/../DCIM"),
        ) shouldBe false
    }

    @Test
    fun `paths of a different type never collide`() {
        val safRoot = "content://com.android.externalstorage.documents/tree/primary"
        validate(
            dir("/storage/emulated/0/DCIM"),
            destination = SAFPath.build(safRoot, "DCIM"),
        ) shouldBe true
    }
}
