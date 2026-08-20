package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * A tab created to show one file highlights it once it has arrived. Highlights are dropped on every
 * location change, so arriving is the only moment that sticks - and it may only be taken once.
 */
class ExplorerRevealHintTest : BaseTest() {

    private val download = LocalPath.build("/sdcard/Download")
    private val music = LocalPath.build("/sdcard/Music")

    private fun ready(path: LocalPath?) = ExplorerWorkspace.State.Ready(
        currentTarget = path?.let { ExplorerNavigation.Target.Directory(it) },
        currentLocation = path?.let { ExplorerLocation.Directory(path = it, progress = null) },
    )

    @Test
    fun `the wait ends when the requested location is on screen`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(ExplorerWorkspace.State.Initializing)
        val arrived = async { awaitLocation(states, download, 10.seconds) }

        // Requested but nothing loaded yet, then somewhere else entirely: neither is an arrival.
        states.value = ready(null)
        states.value = ready(music)
        arrived.isCompleted shouldBe false

        states.value = ready(download)
        arrived.await() shouldBe true
    }

    @Test
    fun `the wait ends once, so navigating away cannot fire it again`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(ready(download))

        awaitLocation(states, download, 10.seconds) shouldBe true

        // Leaving and coming back is a new navigation, not a second reveal of the original hint -
        // and the hint itself is gone by then (see ExplorerWorkspaceArgumentsTest).
        states.value = ready(music)
        states.value = ready(download)
        states.value shouldBe ready(download)
    }

    @Test
    fun `a location that never arrives gives up instead of waiting forever`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(ready(music))

        awaitLocation(states, download, 10.seconds) shouldBe false
    }

    @Test
    fun `an error state is not an arrival`() = runTest {
        val states = MutableStateFlow<ExplorerWorkspace.State>(
            ExplorerWorkspace.State.Error(IllegalStateException("mount gone")),
        )

        awaitLocation(states, download, 10.seconds) shouldBe false
    }
}
