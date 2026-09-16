package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.ui.explorer.dialogs.RenameResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerRenameDestinationTest : BaseTest() {

    @Test
    fun `a rename in a folder listing targets that folder`() {
        val result = RenameResult(
            item = LocalPath.build("/storage/emulated/0/Documents/report.pdf"),
            newName = "invoice.pdf",
        )

        renameDestination(result) shouldBe LocalPath.build("/storage/emulated/0/Documents/invoice.pdf")
    }

    /**
     * Recent is an index of files scattered across storage, so there is no current folder to rename
     * into - the renamed row's own parent is the only folder the new name can mean.
     */
    @Test
    fun `a rename in the Recent listing targets the file's own folder`() {
        val result = RenameResult(
            item = LocalPath.build("/storage/emulated/0/Download/report.pdf"),
            newName = "invoice.pdf",
        )

        renameDestination(result) shouldBe LocalPath.build("/storage/emulated/0/Download/invoice.pdf")
    }
}
