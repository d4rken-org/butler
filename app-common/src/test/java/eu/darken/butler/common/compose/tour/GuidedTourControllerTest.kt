package eu.darken.butler.common.compose.tour

import androidx.navigation3.runtime.NavKey
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.tour.TourPreferences
import eu.darken.butler.common.tour.TourSettings
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class GuidedTourControllerTest : BaseTest() {

    private lateinit var tourSettings: TourSettings

    private lateinit var prefsFlow: MutableStateFlow<TourPreferences>
    private lateinit var prefsValue: DataStoreValue<TourPreferences>
    private lateinit var enabledFlow: MutableStateFlow<Boolean>
    private lateinit var enabledValue: DataStoreValue<Boolean>

    private val basicDefinition = TourDefinition(
        id = TourId("test.basic"),
        steps = listOf(
            TourStep(stepId = "a", body = "A".toCaString()),
            TourStep(stepId = "b", body = "B".toCaString()),
            TourStep(stepId = "c", body = "C".toCaString()),
        ),
        clickProtection = true,
    )

    private val unprotectedDefinition = basicDefinition.copy(
        id = TourId("test.unprotected"),
        clickProtection = false,
    )

    @BeforeEach
    fun setup() {
        prefsFlow = MutableStateFlow(TourPreferences())
        prefsValue = mockk {
            every { flow } returns prefsFlow
            coEvery { update(any<(TourPreferences) -> TourPreferences?>()) } coAnswers {
                val fn = firstArg<(TourPreferences) -> TourPreferences?>()
                val old = prefsFlow.value
                val new = fn(old) ?: old
                prefsFlow.value = new
                DataStoreValue.Updated(old, new)
            }
        }

        enabledFlow = MutableStateFlow(true)
        enabledValue = mockk {
            every { flow } returns enabledFlow
            coEvery { update(any<(Boolean) -> Boolean?>()) } coAnswers {
                val fn = firstArg<(Boolean) -> Boolean?>()
                val old = enabledFlow.value
                val new = fn(old) ?: old
                enabledFlow.value = new
                DataStoreValue.Updated(old, new)
            }
        }

        tourSettings = mockk {
            every { tourPreferences } returns prefsValue
            every { isGuidedToursEnabled } returns enabledValue
        }
    }

    private fun TestScope.controller(): GuidedTourController = GuidedTourController(
        tourSettings = tourSettings,
        scope = this,
    )

    @Test
    fun `shouldStart is true on a fresh tour`() = runTest {
        controller().shouldStart(basicDefinition) shouldBe true
    }

    @Test
    fun `shouldStart is false when completed`() = runTest {
        prefsFlow.value = TourPreferences(completed = setOf(basicDefinition.id.raw))
        controller().shouldStart(basicDefinition) shouldBe false
    }

    @Test
    fun `shouldStart is false when dismissed`() = runTest {
        prefsFlow.value = TourPreferences(dismissed = setOf(basicDefinition.id.raw))
        controller().shouldStart(basicDefinition) shouldBe false
    }

    @Test
    fun `shouldStart is false when a session is active`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.shouldStart(basicDefinition) shouldBe false
    }

    @Test
    fun `shouldStart is false when guided tours are globally disabled`() = runTest {
        enabledFlow.value = false
        controller().shouldStart(basicDefinition) shouldBe false
    }

    @Test
    fun `start no-ops when guided tours are globally disabled`() = runTest {
        enabledFlow.value = false
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.session.value shouldBe null
    }

    @Test
    fun `reset re-enables guided tours`() = runTest {
        enabledFlow.value = false
        controller().reset()
        enabledFlow.value shouldBe true
    }

    @Test
    fun `disableAllTours clears the session and persists the flag off`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.disableAllTours()
        ctrl.session.value shouldBe null
        enabledFlow.value shouldBe false
    }

    @Test
    fun `disableAllTours leaves per-tour preferences untouched`() = runTest {
        prefsFlow.value = TourPreferences(completed = setOf("x"), dismissed = setOf("y"))
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.disableAllTours()
        // The global flag dominates shouldStart(); per-tour state is reset() territory, not this.
        prefsFlow.value shouldBe TourPreferences(completed = setOf("x"), dismissed = setOf("y"))
    }

    @Test
    fun `disableAllTours with no active session still disables`() = runTest {
        val ctrl = controller()
        ctrl.disableAllTours()
        ctrl.session.value shouldBe null
        enabledFlow.value shouldBe false
    }

    @Test
    fun `disableAllTours suppresses all tours until reset re-enables them`() = runTest {
        val ctrl = controller()
        ctrl.disableAllTours()
        ctrl.shouldStart(basicDefinition) shouldBe false
        ctrl.reset()
        ctrl.shouldStart(basicDefinition) shouldBe true
    }

    @Test
    fun `start no-ops when blocked by completed prefs`() = runTest {
        prefsFlow.value = TourPreferences(completed = setOf(basicDefinition.id.raw))
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.session.value shouldBe null
    }

    @Test
    fun `tryStart reports whether it published the session`() = runTest {
        val ctrl = controller()
        ctrl.tryStart(basicDefinition) shouldBe true
        ctrl.session.value shouldBe TourSession(basicDefinition, 0)
    }

    @Test
    fun `tryStart returns false when the tour is already completed`() = runTest {
        prefsFlow.value = TourPreferences(completed = setOf(basicDefinition.id.raw))
        val ctrl = controller()
        ctrl.tryStart(basicDefinition) shouldBe false
        ctrl.session.value shouldBe null
    }

    @Test
    fun `two racing tryStart calls publish exactly one session`() = runTest {
        val ctrl = controller()
        val results = mutableListOf<Boolean>()
        val first = launch { results += ctrl.tryStart(basicDefinition) }
        val second = launch { results += ctrl.tryStart(basicDefinition) }
        first.join()
        second.join()
        results.count { it } shouldBe 1
        results.size shouldBe 2
        ctrl.session.value shouldBe TourSession(basicDefinition, 0)
    }

    @Test
    fun `a tryStart blocked by another session leaves the tour eligible`() = runTest {
        val ctrl = controller()
        val other = basicDefinition.copy(id = TourId("test.other"))
        ctrl.tryStart(other) shouldBe true
        // Blocked purely by the active session — this must not burn the tour.
        ctrl.tryStart(basicDefinition) shouldBe false
        ctrl.skipForNow()
        ctrl.tryStart(basicDefinition) shouldBe true
    }

    @Test
    fun `next advances stepIndex`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.session.value!!.stepIndex shouldBe 0
        ctrl.nextFromCurrent()
        ctrl.session.value!!.stepIndex shouldBe 1
    }

    @Test
    fun `two next calls for the same step advance exactly one step`() = runTest {
        // A double tap on Next launches two coroutines; the second one still names the step it was
        // tapped for. Without the identity guard both would advance.
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.next(basicDefinition.id, "a", basicDefinition)
        ctrl.next(basicDefinition.id, "a", basicDefinition)
        ctrl.session.value!!.stepIndex shouldBe 1
    }

    @Test
    fun `next with a stale step id is a no-op`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.nextFromCurrent()
        ctrl.session.value!!.stepIndex shouldBe 1
        ctrl.next(basicDefinition.id, "a", basicDefinition) // the step the session has already left
        ctrl.session.value!!.stepIndex shouldBe 1
    }

    @Test
    fun `next for a different tour is a no-op`() = runTest {
        // Step ids are not unique across definitions, so the tour has to be checked as well.
        val ctrl = controller()
        ctrl.start(basicDefinition)
        // The live definition is passed, so the tour id is the only part of the identity that differs.
        ctrl.next(TourId("test.other"), "a", basicDefinition)
        ctrl.session.value!!.stepIndex shouldBe 0
    }

    @Test
    fun `a stale next on the last step does not complete the tour`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.markAllStepsRendered(basicDefinition)
        ctrl.nextFromCurrent()
        ctrl.nextFromCurrent() // now on the last step "c"
        ctrl.next(basicDefinition.id, "b", basicDefinition)
        ctrl.session.value!!.stepIndex shouldBe 2
        prefsFlow.value.completed shouldBe emptySet()
    }

    @Test
    fun `next from last step completes tour and persists completed`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.markAllStepsRendered(basicDefinition) // host showed every step — a real walkthrough, persists
        ctrl.nextFromCurrent()
        ctrl.nextFromCurrent()
        ctrl.nextFromCurrent() // last → complete
        ctrl.session.value shouldBe null
        prefsFlow.value.completed shouldBe setOf(basicDefinition.id.raw)
        prefsFlow.value.dismissed shouldBe emptySet()
    }

    @Test
    fun `successful completion invokes onComplete after persistence and session clearing`() = runTest {
        lateinit var ctrl: GuidedTourController
        var completionCalls = 0
        val def = basicDefinition.copy(
            id = TourId("test.oncomplete"),
            onComplete = {
                ctrl.session.value shouldBe null
                prefsFlow.value.completed shouldBe setOf("test.oncomplete")
                completionCalls++
            },
        )
        ctrl = controller()
        ctrl.start(def)
        ctrl.markAllStepsRendered(def)

        ctrl.nextFromCurrent()
        ctrl.nextFromCurrent()
        ctrl.nextFromCurrent() // Finish on the last step.

        completionCalls shouldBe 1
    }

    @Test
    fun `non-completion exits do not invoke onComplete`() = runTest {
        var completionCalls = 0
        fun definition(id: String) = basicDefinition.copy(
            id = TourId(id),
            onComplete = { completionCalls++ },
        )

        controller().apply {
            start(definition("test.oncomplete.skip"))
            skipForNow()
        }
        controller().apply {
            start(definition("test.oncomplete.dismiss"))
            dismissForever()
        }
        controller().apply {
            start(definition("test.oncomplete.disable"))
            disableAllTours()
        }

        completionCalls shouldBe 0
    }

    @Test
    fun `no-render completion fallback does not invoke onComplete`() = runTest {
        var completionCalls = 0
        val def = basicDefinition.copy(
            id = TourId("test.oncomplete.no-render"),
            onComplete = { completionCalls++ },
        )
        val ctrl = controller()
        ctrl.start(def)

        ctrl.complete()

        completionCalls shouldBe 0
    }

    @Test
    fun `next to end without any rendered step skips instead of persisting`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition) // no markStepRendered: every step grace-skipped, nothing shown
        ctrl.nextFromCurrent()
        ctrl.nextFromCurrent()
        ctrl.nextFromCurrent() // last → would complete, but nothing rendered → skip-for-now
        ctrl.session.value shouldBe null
        prefsFlow.value.completed shouldBe emptySet()
        prefsFlow.value.dismissed shouldBe emptySet()
        // Suppressed in-memory for this process, but eligible again after an app restart.
        ctrl.shouldStart(basicDefinition) shouldBe false
        controller().shouldStart(basicDefinition) shouldBe true
    }

    @Test
    fun `complete with a step that never rendered does not persist and stays eligible after restart`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        // The user walked the first two steps; the third grace-skipped for want of an anchor.
        ctrl.markStepRendered(basicDefinition.id, "a")
        ctrl.markStepRendered(basicDefinition.id, "b")
        ctrl.complete()
        ctrl.session.value shouldBe null
        prefsFlow.value.completed shouldBe emptySet()
        prefsFlow.value.dismissed shouldBe emptySet()
        ctrl.shouldStart(basicDefinition) shouldBe false
        controller().shouldStart(basicDefinition) shouldBe true
    }

    @Test
    fun `a repeated render of the same step does not stand in for the missing ones`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        repeat(3) { ctrl.markStepRendered(basicDefinition.id, "a") }
        ctrl.complete()
        prefsFlow.value.completed shouldBe emptySet()
    }

    @Test
    fun `previous decrements stepIndex`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.nextFromCurrent()
        ctrl.session.value!!.stepIndex shouldBe 1
        ctrl.previous()
        ctrl.session.value!!.stepIndex shouldBe 0
    }

    @Test
    fun `previous at first step is a no-op`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.session.value!!.stepIndex shouldBe 0
        ctrl.previous()
        ctrl.session.value!!.stepIndex shouldBe 0
    }

    @Test
    fun `previous without a session is a no-op`() = runTest {
        val ctrl = controller()
        ctrl.previous()
        ctrl.session.value shouldBe null
    }

    @Test
    fun `dismissForever persists to dismissed and clears session`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.dismissForever()
        ctrl.session.value shouldBe null
        prefsFlow.value.dismissed shouldBe setOf(basicDefinition.id.raw)
        prefsFlow.value.completed shouldBe emptySet()
    }

    @Test
    fun `skipForNow clears the session without persistence`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.skipForNow()
        ctrl.session.value shouldBe null
        prefsFlow.value.dismissed shouldBe emptySet()
        prefsFlow.value.completed shouldBe emptySet()
    }

    @Test
    fun `skipForNow suppresses the tour for the rest of the session without persisting`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.skipForNow()
        // No persistence — a freshly constructed controller (simulating an app restart) treats the
        // tour as eligible again.
        prefsFlow.value.dismissed shouldBe emptySet()
        prefsFlow.value.completed shouldBe emptySet()
        controller().shouldStart(basicDefinition) shouldBe true
        // Same controller (current process) keeps the skip in memory.
        ctrl.shouldStart(basicDefinition) shouldBe false
        ctrl.start(basicDefinition)
        ctrl.session.value shouldBe null
    }

    @Test
    fun `reset clears in-memory skip so the tour is eligible again`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.skipForNow()
        ctrl.shouldStart(basicDefinition) shouldBe false
        ctrl.reset()
        ctrl.shouldStart(basicDefinition) shouldBe true
    }

    @Test
    fun `complete persists to completed and clears session`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.markAllStepsRendered(basicDefinition)
        ctrl.complete()
        ctrl.session.value shouldBe null
        prefsFlow.value.completed shouldBe setOf(basicDefinition.id.raw)
    }

    @Test
    fun `complete with no rendered step does not persist and stays eligible after restart`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        ctrl.complete() // nothing ever rendered → must not burn the tour
        ctrl.session.value shouldBe null
        prefsFlow.value.completed shouldBe emptySet()
        prefsFlow.value.dismissed shouldBe emptySet()
        controller().shouldStart(basicDefinition) shouldBe true
    }

    @Test
    fun `markStepRendered ignores a tour id that is not the active session`() = runTest {
        val ctrl = controller()
        ctrl.start(basicDefinition)
        // A late render callback for a different (already-ended) tour must not mark this session.
        basicDefinition.steps.forEach { ctrl.markStepRendered(TourId("some.other.tour"), it.stepId) }
        ctrl.complete()
        prefsFlow.value.completed shouldBe emptySet()
        controller().shouldStart(basicDefinition) shouldBe true
    }

    @Test
    fun `reset clears completed and dismissed sets`() = runTest {
        prefsFlow.value = TourPreferences(
            completed = setOf("a", "b"),
            dismissed = setOf("c"),
        )
        controller().reset()
        prefsFlow.value shouldBe TourPreferences()
    }

    @Test
    fun `route snapshot regression — onRouteChanged before start seeds the start route`() = runTest {
        val ctrl = controller()
        val routeA: NavKey = TestRoute("a")
        val routeB: NavKey = TestRoute("b")
        // Simulate MainActivity emitting the current route BEFORE the screen auto-starts the tour.
        ctrl.onRouteChanged(routeA)
        ctrl.start(unprotectedDefinition)
        ctrl.markAllStepsRendered(unprotectedDefinition) // the whole tour was walked before navigating away
        ctrl.session.value shouldBe TourSession(unprotectedDefinition, 0)
        // User navigates away → the controller must auto-complete (regression for the stale-seed bug).
        ctrl.onRouteChanged(routeB)
        // onRouteChanged launches complete() into the controller's scope. Let the test scheduler run.
        runCurrentUntilIdle()
        ctrl.session.value shouldBe null
        prefsFlow.value.completed shouldBe setOf(unprotectedDefinition.id.raw)
    }

    @Test
    fun `same route after start is no-op`() = runTest {
        val ctrl = controller()
        val routeA: NavKey = TestRoute("a")
        ctrl.onRouteChanged(routeA)
        ctrl.start(unprotectedDefinition)
        ctrl.onRouteChanged(routeA)
        runCurrentUntilIdle()
        ctrl.session.value!!.stepIndex shouldBe 0
    }

    @Test
    fun `clickProtection true ignores route changes`() = runTest {
        val ctrl = controller()
        val routeA: NavKey = TestRoute("a")
        val routeB: NavKey = TestRoute("b")
        ctrl.onRouteChanged(routeA)
        ctrl.start(basicDefinition) // basicDefinition has clickProtection = true
        ctrl.onRouteChanged(routeB)
        runCurrentUntilIdle()
        ctrl.session.value!!.stepIndex shouldBe 0
        prefsFlow.value.completed shouldBe emptySet()
    }

    @Test
    fun `first-step prepareTarget is awaited before session is published`() = runTest {
        val signal = CompletableDeferred<Unit>()
        val def = TourDefinition(
            id = TourId("test.firstprep"),
            steps = listOf(
                TourStep(
                    stepId = "first",
                    body = "first".toCaString(),
                    prepareTarget = { signal.await() },
                ),
                TourStep(stepId = "second", body = "second".toCaString()),
            ),
        )
        val ctrl = controller()
        val startJob = launch { ctrl.start(def) }
        runCurrentUntilIdle()
        // Session must not be published while prepareTarget is still running.
        ctrl.session.value shouldBe null
        signal.complete(Unit)
        startJob.join()
        ctrl.session.value shouldBe TourSession(def, 0)
    }

    @Test
    fun `next-step prepareTarget runs before stepIndex advances`() = runTest {
        val signal = CompletableDeferred<Unit>()
        val def = TourDefinition(
            id = TourId("test.nextprep"),
            steps = listOf(
                TourStep(stepId = "first", body = "first".toCaString()),
                TourStep(
                    stepId = "second",
                    body = "second".toCaString(),
                    prepareTarget = { signal.await() },
                ),
            ),
        )
        val ctrl = controller()
        ctrl.start(def)
        val advanceJob: Job = launch { ctrl.nextFromCurrent() }
        runCurrentUntilIdle()
        // While prepareTarget is suspended, the index must NOT have advanced.
        ctrl.session.value!!.stepIndex shouldBe 0
        signal.complete(Unit)
        advanceJob.join()
        ctrl.session.value!!.stepIndex shouldBe 1
    }

    @Test
    fun `dismissForever during suspended prepareTarget waits its turn and lands dismissal`() = runTest {
        val signal = CompletableDeferred<Unit>()
        val def = TourDefinition(
            id = TourId("test.mutex"),
            steps = listOf(
                TourStep(stepId = "first", body = "first".toCaString()),
                TourStep(
                    stepId = "second",
                    body = "second".toCaString(),
                    prepareTarget = { signal.await() },
                ),
            ),
        )
        val ctrl = controller()
        ctrl.start(def)
        val nextJob = launch { ctrl.nextFromCurrent() } // suspends inside prepareTarget under the mutex
        runCurrentUntilIdle()
        val dismissJob = launch { ctrl.dismissForever() } // will queue behind the mutex
        runCurrentUntilIdle()
        // dismissForever() is blocked: session still active, no dismissal yet.
        ctrl.session.value shouldBe TourSession(def, 0)
        prefsFlow.value.dismissed shouldBe emptySet()
        // Releasing prepareTarget allows next() to finish, then dismissForever() runs.
        signal.complete(Unit)
        nextJob.join()
        dismissJob.join()
        ctrl.session.value shouldBe null
        prefsFlow.value.dismissed shouldBe setOf(def.id.raw)
    }

    /** Stands in for one composition's build of the same tour: its hooks write to [sink]. */
    private fun refreshableDefinition(
        sink: MutableList<String>,
        ownerKey: String? = "pane.1",
        stepIds: List<String> = listOf("one", "two"),
    ) = TourDefinition(
        id = TourId("test.refresh"),
        ownerKey = ownerKey,
        steps = stepIds.map { stepId ->
            TourStep(
                stepId = stepId,
                body = stepId.toCaString(),
                prepareTarget = { sink += stepId },
            )
        },
    )

    @Test
    fun `a refreshed definition swaps the prepare hooks in place`() = runTest {
        val staleSink = mutableListOf<String>()
        val freshSink = mutableListOf<String>()
        val stale = refreshableDefinition(staleSink)
        val fresh = refreshableDefinition(freshSink)
        val ctrl = controller()
        ctrl.start(stale)
        // What an activity recreation looks like from here: same tour, rebuilt hooks.
        ctrl.tryStart(fresh) shouldBe false
        ctrl.next(fresh.id, "one", fresh)
        // The step on screen is re-prepared, and the advance runs the fresh hook, not the stale one.
        freshSink shouldBe listOf("one", "two")
        staleSink shouldBe listOf("one")
        ctrl.session.value!!.stepIndex shouldBe 1
    }

    @Test
    fun `re-submitting the same definition instance does not re-run its prepare hook`() = runTest {
        val sink = mutableListOf<String>()
        val def = refreshableDefinition(sink)
        val ctrl = controller()
        ctrl.start(def)
        ctrl.tryStart(def) shouldBe false
        sink shouldBe listOf("one")
    }

    @Test
    fun `a definition with different steps is not adopted`() = runTest {
        val staleSink = mutableListOf<String>()
        val otherSink = mutableListOf<String>()
        val stale = refreshableDefinition(staleSink)
        val other = refreshableDefinition(otherSink, stepIds = listOf("one", "different"))
        val ctrl = controller()
        ctrl.start(stale)
        ctrl.tryStart(other) shouldBe false
        ctrl.session.value!!.definition shouldBeSameInstanceAs stale
        otherSink shouldBe emptyList()
    }

    @Test
    fun `a second instance of the same tour does not adopt the first`() = runTest {
        val firstSink = mutableListOf<String>()
        val secondSink = mutableListOf<String>()
        val first = refreshableDefinition(firstSink, ownerKey = "pane.1")
        val second = refreshableDefinition(secondSink, ownerKey = "pane.2")
        val ctrl = controller()
        ctrl.start(first)
        ctrl.tryStart(second) shouldBe false
        ctrl.session.value!!.definition shouldBeSameInstanceAs first
        secondSink shouldBe emptyList()
    }

    @Test
    fun `a request naming the definition adoption replaced is dropped`() = runTest {
        val prepareEntered = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        fun definition(prepare: (suspend () -> Unit)?) = TourDefinition(
            id = TourId("test.adopt.race.identity"),
            ownerKey = "pane.1",
            steps = listOf(TourStep(stepId = "one", body = "one".toCaString(), prepareTarget = prepare)),
        )
        val stale = definition(null)
        val fresh = definition {
            prepareEntered.complete(Unit)
            releasePrepare.await()
        }
        val ctrl = controller()
        ctrl.start(stale)

        val adoptJob = launch { ctrl.tryStart(fresh) }
        runCurrentUntilIdle()
        prepareEntered.isCompleted shouldBe true

        // The grace window opened before the rebuild, so the request names the definition the
        // adoption has since replaced.
        val skipJob = launch { ctrl.next(fresh.id, "one", fromDefinition = stale) }
        runCurrentUntilIdle()

        releasePrepare.complete(Unit)
        adoptJob.join()
        skipJob.join()

        val session = ctrl.session.value.shouldNotBeNull()
        session.stepIndex shouldBe 0
        session.definition shouldBeSameInstanceAs fresh
    }

    @Test
    fun `a grace request captured while adoption is parked does not skip the restored step`() = runTest {
        val prepareEntered = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        // One step only: a grace-skip on the last step runs the completion path, so a request that
        // gets through here ends the session outright instead of merely advancing it.
        fun definition(prepare: (suspend () -> Unit)?) = TourDefinition(
            id = TourId("test.adopt.race.host"),
            ownerKey = "pane.1",
            steps = listOf(TourStep(stepId = "one", body = "one".toCaString(), prepareTarget = prepare)),
        )
        val stale = definition(null)
        val fresh = definition {
            prepareEntered.complete(Unit)
            releasePrepare.await()
        }
        val ctrl = controller()
        ctrl.start(stale)

        val adoptJob = launch { ctrl.tryStart(fresh) }
        runCurrentUntilIdle()
        prepareEntered.isCompleted shouldBe true
        releasePrepare.isCompleted shouldBe false

        // GuidedTourHost keys its missing-target grace window on the session object, so a session
        // republished mid-adoption restarts that timer, and the request it fires names whatever
        // definition the session held at the moment the timer started.
        val capturedByTimer = ctrl.session.value.shouldNotBeNull().definition
        val skipJob = launch { ctrl.next(fresh.id, "one", fromDefinition = capturedByTimer) }
        runCurrentUntilIdle()

        releasePrepare.complete(Unit)
        adoptJob.join()
        skipJob.join()

        val session = ctrl.session.value.shouldNotBeNull()
        session.stepIndex shouldBe 0
        session.definition shouldBeSameInstanceAs fresh
    }
}

@Serializable
private data class TestRoute(val tag: String) : NavKey

/** Report every step as rendered — what the host sends when the user walks the whole tour. */
private fun GuidedTourController.markAllStepsRendered(definition: TourDefinition) {
    definition.steps.forEach { markStepRendered(definition.id, it.stepId) }
}

/** Advance from whatever step is current — the identity the real bubble would send. */
private suspend fun GuidedTourController.nextFromCurrent() {
    val s = session.value ?: return
    next(s.definition.id, s.currentStep.stepId, s.definition)
}

private fun TestScope.runCurrentUntilIdle() {
    testScheduler.advanceUntilIdle()
}
