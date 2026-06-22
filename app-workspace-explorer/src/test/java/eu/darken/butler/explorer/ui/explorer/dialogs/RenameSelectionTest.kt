package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.ui.text.TextRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class RenameSelectionTest : BaseTest() {

    @Test
    fun `selects stem for a regular file with extension`() {
        initialRenameSelection("file.txt") shouldBe TextRange(0, 4)
    }

    @Test
    fun `selects whole name for a dotfile`() {
        // ".gitignore": dot at index 0 must not collapse the selection to nothing.
        initialRenameSelection(".gitignore") shouldBe TextRange(0, 10)
    }

    @Test
    fun `selects whole name when there is no extension`() {
        initialRenameSelection("Documents") shouldBe TextRange(0, 9)
    }

    @Test
    fun `selects up to last dot for multi-dot names`() {
        initialRenameSelection("archive.tar.gz") shouldBe TextRange(0, 11)
    }
}
