package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class BrowsingEngineTest : BaseTest() {

    @Nested
    inner class StateTests {

        @Test
        fun `State - initial state has null values`() {
            val state = BrowsingEngine.State()

            state.location shouldBe null
            state.error shouldBe null
            state.breadcrumbs shouldBe null
        }

        @Test
        fun `State - copy preserves location when setting error`() {
            val location = ExplorerLocation.Home(
                items = emptyList(),
                progress = null,
            )
            val state = BrowsingEngine.State(location = location)

            val copied = state.copy(error = RuntimeException("test"))

            copied.location shouldBe location
            copied.error?.message shouldBe "test"
        }

        @Test
        fun `State - error can be set with null location`() {
            val state = BrowsingEngine.State()

            val errorState = state.copy(error = RuntimeException("fail"), location = null)

            errorState.location shouldBe null
            errorState.error?.message shouldBe "fail"
        }
    }

    @Nested
    inner class ExplorerLocationTests {

        @Test
        fun `Home location - isLoading is true when progress is non-null`() {
            val loading = ExplorerLocation.Home(progress = Progress.Data())
            val notLoading = ExplorerLocation.Home(progress = null)

            loading.isLoading shouldBe true
            notLoading.isLoading shouldBe false
        }

        @Test
        fun `Device location - isLoading is true when progress is non-null`() {
            val loading = ExplorerLocation.Device(progress = Progress.Data())
            val notLoading = ExplorerLocation.Device(progress = null)

            loading.isLoading shouldBe true
            notLoading.isLoading shouldBe false
        }

        @Test
        fun `Directory location - isLoading is true when progress is non-null`() {
            val path = LocalPath.build("/sdcard")
            val loading = ExplorerLocation.Directory(path = path, progress = Progress.Data())
            val notLoading = ExplorerLocation.Directory(path = path, progress = null)

            loading.isLoading shouldBe true
            notLoading.isLoading shouldBe false
        }

        @Test
        fun `Home location - locationId is constant`() {
            val home1 = ExplorerLocation.Home()
            val home2 = ExplorerLocation.Home(items = emptyList())

            home1.locationId shouldBe "location://home"
            home2.locationId shouldBe "location://home"
            home1.locationId shouldBe home2.locationId
        }

        @Test
        fun `Device location - locationId is constant`() {
            val device = ExplorerLocation.Device()

            device.locationId shouldBe "location://device"
        }

        @Test
        fun `Directory location - locationId includes path`() {
            val path1 = LocalPath.build("/sdcard/Documents")
            val path2 = LocalPath.build("/sdcard/Pictures")

            val dir1 = ExplorerLocation.Directory(path = path1)
            val dir2 = ExplorerLocation.Directory(path = path2)

            dir1.locationId shouldBe "location://directory//sdcard/Documents"
            dir2.locationId shouldBe "location://directory//sdcard/Pictures"
        }
    }

    private val path = LocalPath.build("/sdcard/Documents")

    private fun entry(name: String) = ExplorerItem.RegularDirectory(
        lookup = LocalPathLookup(
            lookedUp = path.child(name),
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = null,
        ),
    )

    private fun peek(name: String) = ExplorerItem.Peek(path.child(name))

    private fun loaded(vararg items: ExplorerItem.Path) = ExplorerLocation.Directory(
        path = path,
        items = items.toList(),
        info = ExplorerLocation.Directory.Info(fileCount = items.size),
        progress = null,
    )

    private fun loading(items: List<ExplorerItem.Path>? = null) = ExplorerLocation.Directory(
        path = path,
        items = items,
        progress = Progress.Data(),
    )

    @Nested
    inner class RetainContentTests {

        @Test
        fun `a reload that has no items yet keeps the previous content`() {
            val previous = loaded(entry("a"), entry("b"))

            val retained = loading().retainContentFrom(previous)

            retained.items shouldBe previous.items
            retained.info shouldBe previous.info
            retained.isLoading shouldBe true
        }

        @Test
        fun `a reload still in its peek stage keeps the previous content`() {
            val previous = loaded(entry("a"), entry("b"))

            val retained = loading(listOf(peek("a"), peek("b"))).retainContentFrom(previous)

            retained.items shouldBe previous.items
        }

        @Test
        fun `a reload publishes its own listing as soon as it has one`() {
            val previous = loaded(entry("a"), entry("b"))
            val reloaded = loading(listOf(entry("c")))

            reloaded.retainContentFrom(previous) shouldBe reloaded
        }

        @Test
        fun `a location that emptied out is not papered over with the previous content`() {
            val previous = loaded(entry("a"))
            val reloaded = loading(emptyList())

            reloaded.retainContentFrom(previous) shouldBe reloaded
        }

        @Test
        fun `nothing is retained once the load has finished`() {
            val previous = loaded(entry("a"))
            val finished = ExplorerLocation.Directory(path = path, progress = null)

            finished.retainContentFrom(previous) shouldBe finished
        }

        @Test
        fun `nothing is retained when there was no previous content`() {
            val previous = ExplorerLocation.Directory(path = path, progress = null)
            val reloaded = loading()

            reloaded.retainContentFrom(previous) shouldBe reloaded
        }

        @Test
        fun `content is only retained from the same kind of location`() {
            val previous = ExplorerLocation.Home(items = listOf(entry("a")), progress = null)
            val reloaded = loading()

            reloaded.retainContentFrom(previous) shouldBe reloaded
        }
    }

    /**
     * The engine wiring around [BrowsingEngine.State.isRefreshing] and the content retention: both
     * hinge on which trigger started a load, which cannot be recovered from the emissions
     * themselves - a loader publishes items long before it clears its progress.
     */
    @Nested
    inner class RefreshWiringTests {

        private val target = ExplorerNavigation.Target.Directory(path)

        /** One flow per expected loader run, so each load can be driven separately. */
        private val runs = ArrayDeque<MutableSharedFlow<ExplorerLocation>>()

        private fun newRun() = MutableSharedFlow<ExplorerLocation>(extraBufferCapacity = 8)
            .also { runs.addLast(it) }

        private suspend fun MutableSharedFlow<ExplorerLocation>.publish(location: ExplorerLocation) {
            subscriptionCount.first { it > 0 }
            emit(location)
        }

        private fun CoroutineScope.newEngine(dispatcher: CoroutineDispatcher): BrowsingEngine {
            val directoryLoader = mockk<DirectoryLocationLoader>().apply {
                every { loadDirectory(any()) } answers { runs.removeFirst() }
            }
            return BrowsingEngine(
                workspaceId = Workspace.Id(),
                workspaceScope = this,
                dispatcherProvider = TestDispatcherProvider(dispatcher),
                homeLocationLoaderFactory = mockk(relaxed = true),
                deviceLocationLoaderFactory = mockk(relaxed = true),
                trashLocationLoaderFactory = mockk(relaxed = true),
                directoryLoaderFactory = mockk {
                    every { create(any()) } returns directoryLoader
                },
                breadcrumbGenerator = mockk(relaxed = true),
            )
        }

        @Test
        fun `loading a new target is not reported as refreshing`() = runTest(UnconfinedTestDispatcher()) {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val initial = newRun()
            val engine = backgroundScope.newEngine(dispatcher)

            engine.setTarget(target)

            // A load that has published items but is still working is exactly what an
            // after-the-fact guess from progress and items would mistake for a refresh.
            initial.publish(loading(listOf(peek("a"))))
            advanceUntilIdle()
            engine.location.value.isRefreshing shouldBe false

            initial.publish(loaded(entry("a")))
            advanceUntilIdle()
            engine.location.value.isRefreshing shouldBe false
        }

        @Test
        fun `refreshing keeps the current listing on screen and reports itself`() = runTest(UnconfinedTestDispatcher()) {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val initial = newRun()
            val engine = backgroundScope.newEngine(dispatcher)

            engine.setTarget(target)
            initial.publish(loaded(entry("a"), entry("b")))
            advanceUntilIdle()

            val reload = newRun()
            engine.refresh()
            advanceUntilIdle()

            reload.publish(loading())
            advanceUntilIdle()
            engine.location.value.run {
                isRefreshing shouldBe true
                location?.items shouldBe listOf(entry("a"), entry("b"))
            }

            reload.publish(loading(listOf(peek("a"), peek("b"), peek("c"))))
            advanceUntilIdle()
            engine.location.value.location?.items shouldBe listOf(entry("a"), entry("b"))

            // Its own listing takes over as soon as there is one, while the load is still running.
            reload.publish(loading(listOf(entry("a"), entry("b"), entry("c"))))
            advanceUntilIdle()
            engine.location.value.run {
                isRefreshing shouldBe true
                location?.items shouldBe listOf(entry("a"), entry("b"), entry("c"))
            }

            reload.publish(loaded(entry("a"), entry("b"), entry("c")))
            advanceUntilIdle()
            engine.location.value.isRefreshing shouldBe false
        }

        @Test
        fun `a refresh is counted before its load reports anything`() = runTest(UnconfinedTestDispatcher()) {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val initial = newRun()
            val engine = backgroundScope.newEngine(dispatcher)

            engine.setTarget(target)
            initial.publish(loaded(entry("a")))
            advanceUntilIdle()
            engine.location.value.refreshId shouldBe 0

            // Counted at the call, not on an emission: a refresh that finishes between two
            // collections of this conflating flow is never observed as running, but the count is
            // still in whichever state the collector does get.
            newRun()
            engine.refresh()
            engine.location.value.refreshId shouldBe 1
        }

        @Test
        fun `navigating away from a running refresh stops reporting it`() = runTest(UnconfinedTestDispatcher()) {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val initial = newRun()
            val engine = backgroundScope.newEngine(dispatcher)

            engine.setTarget(target)
            initial.publish(loaded(entry("a")))
            advanceUntilIdle()

            val reload = newRun()
            engine.refresh()
            advanceUntilIdle()
            reload.publish(loading())
            advanceUntilIdle()
            engine.location.value.isRefreshing shouldBe true

            // The new target's loader is cancelled into existence without an emission of its own,
            // so nothing else would clear the flag until it finally reports.
            newRun()
            engine.setTarget(ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Pictures")))
            engine.location.value.isRefreshing shouldBe false
        }
    }
}
