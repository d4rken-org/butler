package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
     * The engine wiring around [BrowsingEngine.State.isRefreshing], the content retention and
     * [BrowsingEngine.cancelLoad]: all three hinge on which trigger started a load, which cannot be
     * recovered from the emissions themselves - a loader publishes items long before it clears its
     * progress.
     */
    @Nested
    inner class RefreshWiringTests {

        private val target = ExplorerNavigation.Target.Directory(path)

        private val otherPath = LocalPath.build("/sdcard/Pictures")
        private val otherTarget = ExplorerNavigation.Target.Directory(otherPath)

        /** A load for [otherTarget] that has nothing but its peek listing yet. */
        private fun otherPeeking() = ExplorerLocation.Directory(
            path = otherPath,
            items = listOf(ExplorerItem.Peek(otherPath.child("x"))),
            progress = Progress.Data(),
        )

        /** One flow per expected loader run, so each load can be driven separately. */
        private val runs = ArrayDeque<Flow<ExplorerLocation>>()

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
                networkLocationLoaderFactory = mockk(relaxed = true),
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

        @Test
        fun `cancelling a refresh puts the content it was refreshing back`() =
            runTest(UnconfinedTestDispatcher()) {
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
                engine.location.value.isRefreshing shouldBe true

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.RefreshCancelled
                advanceUntilIdle()

                engine.location.value.run {
                    isRefreshing shouldBe false
                    location?.items shouldBe listOf(entry("a"), entry("b"))
                    location?.info shouldBe ExplorerLocation.Directory.Info(fileCount = 2)
                    location?.isLoading shouldBe false
                }
                // The loader run is gone, not just ignored.
                reload.subscriptionCount.value shouldBe 0
            }

        @Test
        fun `cancelling a refresh that already published its own listing restores the original`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                initial.publish(loaded(entry("a"), entry("b")))
                advanceUntilIdle()

                val reload = newRun()
                engine.refresh()
                advanceUntilIdle()
                // Its own listing takes over here, so retainContentFrom stops protecting the
                // original content - only the snapshot can still bring it back.
                reload.publish(loading(listOf(entry("c"))))
                advanceUntilIdle()
                engine.location.value.location?.items shouldBe listOf(entry("c"))

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.RefreshCancelled
                advanceUntilIdle()

                engine.location.value.run {
                    location?.items shouldBe listOf(entry("a"), entry("b"))
                    location?.info shouldBe ExplorerLocation.Directory.Info(fileCount = 2)
                }
            }

        @Test
        fun `cancelling a load for a new target puts the previous location back`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                initial.publish(loaded(entry("a")))
                advanceUntilIdle()
                val breadcrumbs = engine.location.value.breadcrumbs

                val second = newRun()
                engine.setTarget(otherTarget)
                advanceUntilIdle()
                second.publish(otherPeeking())
                advanceUntilIdle()

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NavigationRestored(target)
                advanceUntilIdle()

                val restored = engine.location.value
                restored.location?.items shouldBe listOf(entry("a"))
                restored.target shouldBe target
                restored.breadcrumbs shouldBe breadcrumbs
                restored.isRefreshing shouldBe false
                second.subscriptionCount.value shouldBe 0
            }

        @Test
        fun `cancelling the first load of a workspace reports that there is nothing to restore`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                advanceUntilIdle()
                initial.publish(loading())
                advanceUntilIdle()

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NothingToRestore(target)
                advanceUntilIdle()

                val aborted = engine.location.value
                aborted.location shouldBe null
                aborted.isRefreshing shouldBe false
                (aborted.error as? BrowsingAbortedException)?.target shouldBe target
            }

        @Test
        fun `an emission of a cancelled load never replaces the restored content`() =
            runTest(UnconfinedTestDispatcher()) {
                // The loader side runs inline while the engine's own collection has to be advanced,
                // so an emission can sit between the two: already produced, not yet published -
                // which is the state the generation fence exists for. The engine scope must not be
                // the backgroundScope, advanceUntilIdle() does not run background-only tasks.
                val engineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
                val initial = newRun()
                val engine = engineScope.newEngine(UnconfinedTestDispatcher(testScheduler))

                engine.setTarget(target)
                advanceUntilIdle()
                initial.publish(loaded(entry("a")))
                advanceUntilIdle()
                // Precondition: there is a restore point, so the assertion below cannot pass just
                // because nothing was ever settled.
                engine.location.value.location?.items shouldBe listOf(entry("a"))

                val reload = newRun()
                engine.refresh()
                advanceUntilIdle()

                // Produced before the cancel, published after it: without the fence this listing
                // would come back over the content the cancel just restored.
                reload.emit(loading(listOf(entry("late"))))
                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.RefreshCancelled
                advanceUntilIdle()

                engine.location.value.location?.items shouldBe listOf(entry("a"))
                engine.release()
            }

        /** The engine's own hint mutex, used as a gate to park its pipeline at a known point. */
        private fun BrowsingEngine.hintGate(): Mutex = BrowsingEngine::class.java
            .getDeclaredField("hintMutex")
            .apply { isAccessible = true }
            .get(this) as Mutex

        /**
         * Arranges a settle of the previous target that is delivered after the next load already
         * started: the navigation to it is parked in the engine's hint clearing, so the previous
         * loader is still subscribed and its last emission arrives behind the new load. Such a
         * settle is not stale - it still updates the restore point - but it must not report the
         * load that is running now as finished.
         */
        private suspend fun TestScope.arrangeLateSettleOfPreviousTarget(
            initial: MutableSharedFlow<ExplorerLocation>,
            engine: BrowsingEngine,
        ) {
            engine.setTarget(target)
            initial.publish(loaded(entry("a")))
            advanceUntilIdle()
            engine.location.value.location?.items shouldBe listOf(entry("a"))

            val gate = engine.hintGate()
            gate.lock()
            newRun()
            engine.setTarget(otherTarget)
            advanceUntilIdle()

            initial.emit(loaded(entry("a"), entry("b")))
            advanceUntilIdle()
            engine.location.value.location?.items shouldBe listOf(entry("a"), entry("b"))

            gate.unlock()
            advanceUntilIdle()
        }

        @Test
        fun `a late settle of the previous target leaves the running load cancellable`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                arrangeLateSettleOfPreviousTarget(initial, engine)

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NavigationRestored(target)
                advanceUntilIdle()
                // The late settle updated the restore point, so it is what comes back.
                engine.location.value.location?.items shouldBe listOf(entry("a"), entry("b"))
            }

        @Test
        fun `a late settle of the previous target does not let a refresh through`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                arrangeLateSettleOfPreviousTarget(initial, engine)

                val forbidden = newRun()
                engine.refresh()
                advanceUntilIdle()

                engine.location.value.refreshId shouldBe 0
                forbidden.subscriptionCount.value shouldBe 0
            }

        @Test
        fun `a refresh issued while a cancelled loader winds down still runs`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                initial.publish(loaded(entry("a")))
                advanceUntilIdle()

                // A loader that does not end at cancellation, e.g. one stuck in a single long
                // gateway call. The session handoff cannot complete while it winds down.
                val release = CompletableDeferred<Unit>()
                runs.addLast(
                    flow {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { release.await() }
                        }
                    }
                )
                engine.setTarget(otherTarget)
                advanceUntilIdle()

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NavigationRestored(target)
                val reload = newRun()
                // Issued while the restored session has no subscriber yet: the command has to wait
                // in the session's queue instead of being dropped.
                engine.refresh()
                advanceUntilIdle()

                release.complete(Unit)
                advanceUntilIdle()

                reload.publish(loaded(entry("a"), entry("c")))
                advanceUntilIdle()
                engine.location.value.run {
                    location?.items shouldBe listOf(entry("a"), entry("c"))
                    refreshId shouldBe 1
                    isRefreshing shouldBe false
                }
            }

        @Test
        fun `a refresh cancelled while it was still queued cannot publish`() =
            runTest(UnconfinedTestDispatcher()) {
                // One dispatcher for the engine and its IO context, so a loader's first emission
                // needs no hop and lands the moment the queued command starts it.
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val engineScope = CoroutineScope(dispatcher + Job())
                val initial = newRun()
                val engine = engineScope.newEngine(dispatcher)

                engine.setTarget(target)
                initial.publish(loaded(entry("a")))
                advanceUntilIdle()

                val release = CompletableDeferred<Unit>()
                runs.addLast(
                    flow {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { release.await() }
                        }
                    }
                )
                engine.setTarget(otherTarget)
                advanceUntilIdle()

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NavigationRestored(target)
                // Queued while the handoff is blocked, then cancelled before it could start: the
                // cancel's command sits behind it, so the loader still runs for a moment.
                engine.refresh()
                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.RefreshCancelled

                // Reports itself as loading as soon as it is subscribed.
                runs.addLast(flow { emit(loading()); awaitCancellation() })
                release.complete(Unit)
                advanceUntilIdle()

                engine.location.value.run {
                    location?.items shouldBe listOf(entry("a"))
                    location?.isLoading shouldBe false
                    isRefreshing shouldBe false
                }
                engine.release()
            }

        @Test
        fun `a refresh requested while a new target is loading is ignored`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                initial.publish(loaded(entry("a")))
                advanceUntilIdle()

                val second = newRun()
                engine.setTarget(otherTarget)
                advanceUntilIdle()
                second.publish(otherPeeking())
                advanceUntilIdle()

                // Taking this as a refresh would freeze the partial new listing as the state a
                // cancel restores, instead of the location the user came from.
                engine.refresh()
                advanceUntilIdle()
                engine.location.value.refreshId shouldBe 0

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NavigationRestored(target)
                advanceUntilIdle()
                engine.location.value.location?.items shouldBe listOf(entry("a"))
            }

        @Test
        fun `a cancel restores the listing including incremental updates`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                initial.publish(loaded(entry("a"), entry("b")))
                advanceUntilIdle()

                engine.hint(
                    FileSystemEvent.Removed(
                        operationId = Operation.Id(),
                        paths = setOf(entry("b").lookup),
                    )
                )
                advanceUntilIdle()
                engine.location.value.location?.items shouldBe listOf(entry("a"))

                newRun()
                engine.setTarget(otherTarget)
                advanceUntilIdle()

                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NavigationRestored(target)
                advanceUntilIdle()
                engine.location.value.location?.items shouldBe listOf(entry("a"))
            }

        @Test
        fun `after a cancelled navigation a refresh reloads the restored target`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                val initial = newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                initial.publish(loaded(entry("a")))
                advanceUntilIdle()

                newRun()
                engine.setTarget(otherTarget)
                advanceUntilIdle()
                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NavigationRestored(target)
                advanceUntilIdle()

                // The session stays armed with the restored target, so this starts a load again.
                val reload = newRun()
                engine.refresh()
                advanceUntilIdle()
                reload.publish(loaded(entry("a"), entry("b")))
                advanceUntilIdle()

                engine.location.value.run {
                    location?.items shouldBe listOf(entry("a"), entry("b"))
                    isRefreshing shouldBe false
                }
            }

        @Test
        fun `after an aborted first load the same target can be loaded again`() =
            runTest(UnconfinedTestDispatcher()) {
                val dispatcher = UnconfinedTestDispatcher(testScheduler)
                newRun()
                val engine = backgroundScope.newEngine(dispatcher)

                engine.setTarget(target)
                advanceUntilIdle()
                engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NothingToRestore(target)
                advanceUntilIdle()

                val retry = newRun()
                engine.setTarget(target)
                advanceUntilIdle()
                retry.publish(loaded(entry("a")))
                advanceUntilIdle()

                engine.location.value.run {
                    location?.items shouldBe listOf(entry("a"))
                    error shouldBe null
                }
            }

        @Test
        fun `a cancel without a running load changes nothing`() = runTest(UnconfinedTestDispatcher()) {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val initial = newRun()
            val engine = backgroundScope.newEngine(dispatcher)

            engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NoLoadRunning

            engine.setTarget(target)
            initial.publish(loaded(entry("a")))
            advanceUntilIdle()

            // Navigating to the target that is already displayed starts no load, so there is
            // nothing for a cancel to claim either.
            val settled = engine.location.value
            engine.setTarget(target)
            engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.NoLoadRunning
            advanceUntilIdle()
            engine.location.value shouldBe settled
        }

        @Test
        fun `a refresh after a cancelled refresh runs normally`() = runTest(UnconfinedTestDispatcher()) {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val initial = newRun()
            val engine = backgroundScope.newEngine(dispatcher)

            engine.setTarget(target)
            initial.publish(loaded(entry("a")))
            advanceUntilIdle()

            newRun()
            engine.refresh()
            advanceUntilIdle()
            engine.cancelLoad() shouldBe BrowsingEngine.CancelResult.RefreshCancelled
            advanceUntilIdle()

            val second = newRun()
            engine.refresh()
            advanceUntilIdle()
            second.publish(loaded(entry("a"), entry("b")))
            advanceUntilIdle()

            engine.location.value.run {
                isRefreshing shouldBe false
                location?.items shouldBe listOf(entry("a"), entry("b"))
                refreshId shouldBe 2
            }
        }
    }
}
