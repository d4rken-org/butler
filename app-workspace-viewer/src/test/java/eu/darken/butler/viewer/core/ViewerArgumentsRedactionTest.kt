package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * A caption is text the sender wrote, not Butler's data, and workspace arguments are printed into
 * the debug log that a bug report ships. Only the presence of a caption may show up there.
 */
class ViewerArgumentsRedactionTest : BaseTest() {

    private val path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val caption = "my bank pin is 1234"

    @Test
    fun `a stored file's caption is not printed`() {
        val args = ViewerArguments.Default(
            filePath = path,
            caption = caption,
            callerWorkspaceId = Workspace.Id(),
        )

        args.toString() shouldNotContain caption
        args.toString() shouldContain "caption=<present>"
        // The rest still has to be diagnosable.
        args.toString() shouldContain path.path
        args.toString() shouldContain args.callerWorkspaceId.toString()
    }

    @Test
    fun `streamed content's caption is not printed`() {
        val args = ViewerArguments.Streamed(
            uriString = "content://com.example.files/document/42",
            displayName = "backup.zip",
            mimeType = "application/zip",
            sizeBytes = 4096L,
            arrivalId = "arrival-1",
            caption = caption,
        )

        args.toString() shouldNotContain caption
        args.toString() shouldContain "caption=<present>"
        args.toString() shouldContain "backup.zip"
        args.toString() shouldContain "arrival-1"
    }

    @Test
    fun `an arrival without a caption says so`() {
        ViewerArguments.Default(filePath = path).toString() shouldContain "caption=null"
        ViewerArguments.Streamed(
            uriString = "content://com.example.files/document/42",
            displayName = "backup.zip",
            mimeType = "application/zip",
            sizeBytes = null,
            arrivalId = "arrival-1",
        ).toString() shouldContain "caption=null"
    }

    @Test
    fun `redacting the log form does not change equality`() {
        val withCaption = ViewerArguments.Default(filePath = path, caption = caption)

        // toString is diagnostics only; two different captions are still two different arguments.
        withCaption shouldBe ViewerArguments.Default(filePath = path, caption = caption)
        (withCaption == ViewerArguments.Default(filePath = path, caption = "something else")) shouldBe false
    }
}
