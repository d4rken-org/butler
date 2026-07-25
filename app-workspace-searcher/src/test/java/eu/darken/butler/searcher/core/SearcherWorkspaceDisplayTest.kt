package eu.darken.butler.searcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherWorkspaceDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a filename search is named after its pattern`() {
        val display = deriveSearcherDisplay(
            SearcherArguments.Default(filenameQuery = FilenameQuery(pattern = "*.pdf")),
        )

        display!!.title!!.get(context) shouldBe "*.pdf"
    }

    @Test
    fun `a content search is named after its pattern`() {
        val display = deriveSearcherDisplay(
            SearcherArguments.Default(contentQuery = ContentQuery(pattern = "TODO")),
        )

        display!!.title!!.get(context) shouldBe "TODO"
    }

    @Test
    fun `a filename pattern wins over a content pattern`() {
        val display = deriveSearcherDisplay(
            SearcherArguments.Default(
                filenameQuery = FilenameQuery(pattern = "*.kt"),
                contentQuery = ContentQuery(pattern = "TODO"),
            ),
        )

        display!!.title!!.get(context) shouldBe "*.kt"
    }

    @Test
    fun `blank patterns carry no identity`() {
        deriveSearcherDisplay(
            SearcherArguments.Default(
                filenameQuery = FilenameQuery(pattern = "   "),
                contentQuery = ContentQuery(pattern = ""),
            ),
        ) shouldBe null
    }

    @Test
    fun `search targets describe where the search runs`() {
        val photos = SearchTarget.MediaStore(collection = SearchTarget.MediaStore.Collection.IMAGES)
        val display = deriveSearcherDisplay(
            SearcherArguments.Default(
                filenameQuery = FilenameQuery(pattern = "*.pdf"),
                startTargets = listOf(SearchTarget.Path(path = LocalPath.build("/sdcard/Download")), photos),
            ),
        )

        display!!.subtitle!!.get(context) shouldBe "/sdcard/Download, ${photos.displayText.get(context)}"
    }

    @Test
    fun `disabled targets are left out`() {
        val display = deriveSearcherDisplay(
            SearcherArguments.Default(
                startTargets = listOf(
                    SearchTarget.Path(path = LocalPath.build("/sdcard/Download")),
                    SearchTarget.Path(path = LocalPath.build("/sdcard/DCIM"), enabled = false),
                ),
            ),
        )

        display!!.subtitle!!.get(context) shouldBe "/sdcard/Download"
    }

    @Test
    fun `a long target list collapses instead of growing unbounded`() {
        val targets = (1..7).map { SearchTarget.Path(path = LocalPath.build("/sdcard/dir$it")) }

        val subtitle = searcherTargetsSubtitle(targets)!!.get(context)

        subtitle shouldBe "/sdcard/dir1, /sdcard/dir2, /sdcard/dir3 +4"
        subtitle shouldNotContain "/sdcard/dir4"
    }

    @Test
    fun `targets resolve their own display text instead of rendering an object`() {
        val music = SearchTarget.MediaStore(collection = SearchTarget.MediaStore.Collection.AUDIO)

        val subtitle = searcherTargetsSubtitle(listOf(music))!!.get(context)

        subtitle shouldBe music.displayText.get(context)
        subtitle shouldNotContain "SearchTarget"
    }

    @Test
    fun `only enabled targets with no query still identify the tab`() {
        val display = deriveSearcherDisplay(
            SearcherArguments.Default(
                startTargets = listOf(SearchTarget.Path(path = LocalPath.build("/sdcard"))),
            ),
        )

        display!!.title shouldBe null
        display.subtitle!!.get(context) shouldBe "/sdcard"
    }

    @Test
    fun `empty arguments carry no identity`() {
        deriveSearcherDisplay(SearcherArguments.Default()) shouldBe null
    }

    @Test
    fun `a blank target label is left out of the subtitle`() {
        val subtitle = searcherTargetsSubtitle(
            listOf(
                SearchTarget.Path(path = LocalPath.build("/sdcard/Download"), label = "   "),
                SearchTarget.Path(path = LocalPath.build("/sdcard/DCIM")),
            ),
        )!!.get(context)

        subtitle shouldBe "/sdcard/DCIM"
    }
}
