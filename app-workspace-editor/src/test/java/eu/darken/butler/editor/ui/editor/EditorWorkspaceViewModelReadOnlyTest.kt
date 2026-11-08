package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.charset.Charset

class EditorWorkspaceViewModelReadOnlyTest : BaseTest() {

    private fun file(charset: Charset, hasUndecodableBytes: Boolean) = ContentSource.File(
        path = LocalPath.build("/storage/emulated/0/test.txt"),
        size = 10L,
        lastModified = null,
        canWrite = true,
        detectedCharset = charset,
        hasUndecodableBytes = hasUndecodableBytes,
    )

    private fun state(source: ContentSource) = EditorWorkspaceViewModel.State(
        id = Workspace.Id(),
        contentSource = source,
        title = caString("test.txt"),
        subTitle = caString("/storage/emulated/0/test.txt"),
    )

    @Test
    fun `undecodable GB18030 is read-only and shows its banner`() {
        val state = state(file(TextCharsets.GB18030, hasUndecodableBytes = true))
        state.isUndecodable shouldBe true
        state.isReadOnly shouldBe true
        state.isBinary shouldBe false
    }

    @Test
    fun `valid GB18030 is editable`() {
        val state = state(file(TextCharsets.GB18030, hasUndecodableBytes = false))
        state.isUndecodable shouldBe false
        state.isReadOnly shouldBe false
    }

    @Test
    fun `undecodable bytes in other charsets stay editable`() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.ISO_8859_1, TextCharsets.WINDOWS_1252)) {
            val state = state(file(charset, hasUndecodableBytes = true))
            state.isUndecodable shouldBe false
            state.isReadOnly shouldBe false
        }
    }

    @Test
    fun `the undecodable flag takes part in equality`() {
        file(TextCharsets.GB18030, hasUndecodableBytes = true) shouldNotBe
            file(TextCharsets.GB18030, hasUndecodableBytes = false)
    }
}
