package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * A tab created to show one file highlights it once it has arrived. Highlights are dropped on every
 * location change and the item has to be in the listing to be highlighted, so a loaded arrival is
 * the only moment that sticks - and it may only be taken once.
 */
class ExplorerRevealHintTest : BaseTest() {

    private val download = LocalPath.build("/sdcard/Download")
    private val music = LocalPath.build("/sdcard/Music")

    private fun ready(
        path: LocalPath?,
        items: List<ExplorerItem.Path>? = emptyList(),
        progress: Progress.Data? = null,
    ) = ExplorerWorkspace.State.Ready(
        currentTarget = path?.let { ExplorerNavigation.Target.Directory(it) },
        currentLocation = path?.let { ExplorerLocation.Directory(path = it, items = items, progress = progress) },
    )

    @Test
    fun `the wait ends when the requested location is on screen`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(ExplorerWorkspace.State.Initializing)
        val arrived = async(UnconfinedTestDispatcher(testScheduler)) { awaitLoadedLocation(states, download) }

        // Requested but nothing loaded yet, then somewhere else entirely: neither is an arrival.
        states.value = ready(null)
        states.value = ready(music)
        arrived.isCompleted shouldBe false

        states.value = ready(download)
        arrived.await()
    }

    @Test
    fun `a location that is still loading is not an arrival yet`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(ExplorerWorkspace.State.Initializing)
        val arrived = async(UnconfinedTestDispatcher(testScheduler)) { awaitLoadedLocation(states, download) }

        // The location is published when the navigation starts, before anything was enumerated.
        // Revealing here would highlight an item that isn't in the listing yet.
        states.value = ready(download, items = null, progress = Progress.Data())
        arrived.isCompleted shouldBe false

        // Progress is gone but the listing hasn't been published with it.
        states.value = ready(download, items = null, progress = null)
        arrived.isCompleted shouldBe false

        states.value = ready(download, items = emptyList(), progress = null)
        arrived.await()
    }

    @Test
    fun `the wait ends once, so navigating away cannot fire it again`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(ready(download))

        awaitLoadedLocation(states, download)

        // Leaving and coming back is a new navigation, not a second reveal of the original hint -
        // and the hint itself is gone by then (see ExplorerWorkspaceArgumentsTest).
        states.value = ready(music)
        states.value = ready(download)
        states.value shouldBe ready(download)
    }

    @Test
    fun `an error state is not an arrival`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(
            ExplorerWorkspace.State.Error(IllegalStateException("mount gone")),
        )
        val arrived = async(UnconfinedTestDispatcher(testScheduler)) { awaitLoadedLocation(states, download) }

        states.value = ready(music)
        arrived.isCompleted shouldBe false

        arrived.cancel()
    }
}
