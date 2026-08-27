package eu.darken.butler.workspace.core.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

// Robolectric: the clip description is a CaString, so asserting what it renders needs a context.
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class ClipboardClipDescriptionTest : BaseTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun lookup(path: String) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = FileType.FILE,
        size = null,
        modifiedAt = null,
    )

    private fun clip(vararg paths: String) = ClipboardClip.Paths(
        origin = Workspace.Id(),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = paths.map { lookup(it) },
    )

    @Test
    fun `one parent names the location`() {
        val description = clip(
            "/storage/emulated/0/Download/a.txt",
            "/storage/emulated/0/Download/b.txt",
        ).description.get(context)

        description shouldBe "2 items from /storage/emulated/0/Download"
    }

    @Test
    fun `several parents count the locations instead of naming one`() {
        val description = clip(
            "/storage/emulated/0/Download/a.txt",
            "/storage/emulated/0/Download/b.txt",
            "/storage/emulated/0/Documents/c.txt",
        ).description.get(context)

        description shouldBe "3 items from 2 locations"
    }

    @Test
    fun `the root and an item below it are one location`() {
        val description = clip("/", "/sdcard").description.get(context)

        description shouldBe "2 items from /"
    }

    @Test
    fun `a single path still shows the path itself`() {
        val description = clip("/storage/emulated/0/Download/a.txt").description.get(context)

        description shouldBe "/storage/emulated/0/Download/a.txt"
    }
}
