package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.ExplorerItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * A reveal aimed at a folder may only be answered by that folder's own listing. The engine arrives
 * before the processed listing does, so the first emission after the navigation can still hold the
 * listing that was left behind - and "Show in folder" is started from Recent, which holds the very
 * path being revealed.
 */
class ExplorerRevealIndexTest : BaseTest() {

    private val downloads = "location://directory//storage/emulated/0/Download"
    private val recent = "location://recent"

    private fun path(name: String) = LocalPath.build("/storage/emulated/0/Download/$name")

    private fun file(name: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = path(name),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("application/pdf"),
    )

    private fun state(
        listingLocationId: String,
        items: List<ExplorerItem>,
    ) = ExplorerWorkspaceViewModel.State(
        locationId = downloads,
        listingLocationId = listingLocationId,
        items = items,
    )

    private fun request(destination: String?) = ExplorerWorkspaceViewModel.RevealRequest(
        path = path("report.pdf"),
        destination = destination,
    )

    @Test
    fun `a listing from another location cannot answer a destination-scoped reveal`() {
        val target = state(
            listingLocationId = recent,
            items = listOf(file("photo.jpg"), file("report.pdf")),
        )

        target.revealIndexFor(request(destination = downloads)) shouldBe null
    }

    @Test
    fun `the destination's own listing resolves the index`() {
        val target = state(
            listingLocationId = downloads,
            items = listOf(file("photo.jpg"), file("report.pdf")),
        )

        target.revealIndexFor(request(destination = downloads)) shouldBe 1
    }

    @Test
    fun `the destination's peek listing cannot answer a destination-scoped reveal`() {
        // The loader's peek stage publishes raw listFiles() order under the destination's own
        // locationId. Answering from it scrolls to a row the final ordering moves elsewhere.
        val peeking = state(
            listingLocationId = downloads,
            items = listOf(ExplorerItem.Peek(path("report.pdf")), ExplorerItem.Peek(path("photo.jpg"))),
        )

        peeking.revealIndexFor(request(destination = downloads)) shouldBe null

        val settled = state(
            listingLocationId = downloads,
            items = listOf(file("photo.jpg"), file("report.pdf")),
        )

        settled.revealIndexFor(request(destination = downloads)) shouldBe 1
    }

    @Test
    fun `a reveal without a destination is answered by whatever is on screen`() {
        val target = state(
            listingLocationId = recent,
            items = listOf(file("report.pdf")),
        )

        target.revealIndexFor(request(destination = null)) shouldBe 0
    }
}
