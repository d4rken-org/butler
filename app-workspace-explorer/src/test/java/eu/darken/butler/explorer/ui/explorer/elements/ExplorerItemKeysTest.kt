package eu.darken.butler.explorer.ui.explorer.elements

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.ExplorerItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerItemKeysTest : BaseTest() {

    private fun file(name: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/$name"),
            fileType = FileType.FILE,
            size = null,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    @Test
    fun `unique ids are handed back untouched`() {
        val items = listOf(file("a.txt"), file("b.txt"))

        uniqueItemKeys(items) shouldBe items.map { it.id }
    }

    /**
     * Two documents in one directory can carry the same display name, which makes their paths equal.
     * Compose rejects duplicate keys outright, so before this the second one crashed the listing.
     */
    @Test
    fun `a repeated id is suffixed by occurrence`() {
        val items = listOf(file("dup.txt"), file("other.txt"), file("dup.txt"), file("dup.txt"))

        val keys = uniqueItemKeys(items)

        keys.toSet().size shouldBe keys.size
        keys[0] shouldBe items[0].id
        keys[1] shouldBe items[1].id
        keys[2] shouldBe "${items[2].id}#2"
        keys[3] shouldBe "${items[3].id}#3"
    }

    @Test
    fun `an empty list has no keys`() {
        uniqueItemKeys(emptyList()) shouldBe emptyList()
    }

    private fun uniqueItemKeys(items: List<ExplorerItem>) = items.uniqueItemKeys()
}
