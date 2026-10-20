package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.seconds

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

    private val downloadsDir = LocalPath.build("/storage/emulated/0/Download")
    private val destination = ExplorerLocation.Directory.locationIdOf(downloadsDir)
    private val withRow = listOf(file("photo.jpg"), file("report.pdf"))
    private val withoutRow = listOf(file("photo.jpg"))

    private fun awaiting() = ExplorerWorkspaceViewModel.RevealRequest(
        path = path("report.pdf"),
        destination = destination,
        awaitDestinationLoad = true,
    )

    /** The destination is the current location; [items] is both the engine's and the processed listing. */
    private fun atDestination(
        items: List<ExplorerItem.Path>?,
        progress: Progress.Data? = null,
        listingLocationId: String = destination,
    ) = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Directory(path = downloadsDir, items = items, progress = progress),
        locationId = destination,
        listingLocationId = listingLocationId,
        items = items,
    )

    private fun atRecent() = ExplorerWorkspaceViewModel.State(
        currentLocation = ExplorerLocation.Recent(items = withRow, progress = null),
        locationId = recent,
        listingLocationId = recent,
        items = withRow,
    )

    private fun loading() = atDestination(items = withoutRow, progress = Progress.Data())

    @Test
    fun `an awaiting reveal is pending while its destination loads`() {
        atDestination(items = withRow, progress = Progress.Data()).revealWait(awaiting()) shouldBe RevealWait.Pending
        atDestination(items = withRow, listingLocationId = recent).revealWait(awaiting()) shouldBe RevealWait.Pending
        atDestination(items = listOf(ExplorerItem.Peek(path("report.pdf"))))
            .revealWait(awaiting()) shouldBe RevealWait.Pending
    }

    @Test
    fun `an awaiting reveal is abandoned once another location is current`() {
        atRecent().revealWait(awaiting()) shouldBe RevealWait.Abandoned
    }

    @Test
    fun `an awaiting reveal is abandoned when its destination settled without a listing`() {
        atDestination(items = null).revealWait(awaiting()) shouldBe RevealWait.Abandoned
    }

    @Test
    fun `an awaiting reveal resolves from the settled destination`() {
        atDestination(items = withRow).revealWait(awaiting()) shouldBe RevealWait.Resolved(1)
        atDestination(items = withoutRow).revealWait(awaiting()) shouldBe RevealWait.Resolved(null)
    }

    @Test
    fun `a reveal that does not await the load is never pending`() {
        val loadingWithRow = atDestination(items = withRow, progress = Progress.Data())

        loadingWithRow.revealWait(request(destination = destination)) shouldBe RevealWait.Resolved(1)
        loadingWithRow.revealWait(request(destination = null)) shouldBe RevealWait.Resolved(1)
        atRecent().revealWait(request(destination = destination)) shouldBe RevealWait.Resolved(null)
    }

    @Test
    fun `a pending destination runs no deadline`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspaceViewModel.State?>(loading())
        val result = async { awaitRevealIndex(states, awaiting()) }

        advanceTimeBy(5.seconds)
        result.isCompleted shouldBe false

        states.value = atDestination(items = withRow)
        runCurrent()
        result.isCompleted shouldBe true
        result.await() shouldBe 1
    }

    @Test
    fun `a settled destination gives the row one window to appear`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspaceViewModel.State?>(atDestination(items = withoutRow))
        val result = async { awaitRevealIndex(states, awaiting()) }

        advanceTimeBy(1.seconds)
        states.value = atDestination(items = withRow)
        runCurrent()
        result.isCompleted shouldBe true
        result.await() shouldBe 1
    }

    @Test
    fun `a settled destination gives up once the window passed`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspaceViewModel.State?>(atDestination(items = withoutRow))
        val result = async { awaitRevealIndex(states, awaiting()) }

        advanceTimeBy(3.seconds)
        result.isCompleted shouldBe true
        states.value = atDestination(items = withRow)
        runCurrent()
        result.await() shouldBe null
    }

    @Test
    fun `leaving a pending destination ends the wait right away`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspaceViewModel.State?>(loading())
        val result = async { awaitRevealIndex(states, awaiting()) }

        advanceTimeBy(1.seconds)
        states.value = atRecent()
        runCurrent()
        result.isCompleted shouldBe true
        result.await() shouldBe null
        testScheduler.currentTime shouldBe 1.seconds.inWholeMilliseconds
    }

    @Test
    fun `a reveal that does not await the load keeps the single window`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspaceViewModel.State?>(atDestination(items = withoutRow))
        val found = async { awaitRevealIndex(states, request(destination = null)) }

        advanceTimeBy(1.seconds)
        states.value = atDestination(items = withRow)
        runCurrent()
        found.isCompleted shouldBe true
        found.await() shouldBe 1

        states.value = loading()
        val missed = async { awaitRevealIndex(states, request(destination = null)) }

        advanceTimeBy(3.seconds)
        missed.isCompleted shouldBe true
        states.value = atDestination(items = withRow)
        runCurrent()
        missed.await() shouldBe null
    }

    @Test
    fun `a load that starts after settling pauses the window`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspaceViewModel.State?>(atDestination(items = withoutRow))
        val result = async { awaitRevealIndex(states, awaiting()) }

        advanceTimeBy(1.seconds)
        states.value = loading()
        advanceTimeBy(5.seconds)
        result.isCompleted shouldBe false

        states.value = atDestination(items = withRow)
        runCurrent()
        result.isCompleted shouldBe true
        result.await() shouldBe 1
    }
}
