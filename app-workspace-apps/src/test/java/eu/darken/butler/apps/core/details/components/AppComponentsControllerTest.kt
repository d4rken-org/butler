package eu.darken.butler.apps.core.details.components

import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.pkgs.features.Installed
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class AppComponentsControllerTest : BaseTest() {

    private val activity = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.MainActivity",
        isExported = true,
    )
    private val service = ComponentEntry(
        kind = ComponentKind.SERVICE,
        packageName = "com.example.app",
        className = "com.example.app.sync.SyncService",
        isExported = false,
    )
    private val data = ComponentsData(activities = listOf(activity), services = listOf(service))

    private fun appInfo(
        pkg: String = "com.example.app",
        version: Long = 1L,
    ) = AppInfo(
        install = mockk<Installed> {
            every { packageName } returns pkg
            every { versionCode } returns version
        },
    )

    private fun CoroutineScope.controller(loader: AppComponentsLoader) = AppComponentsController(
        scope = this,
        loader = loader,
    )

    private fun AppComponentsController.enabledStates(): List<ComponentEnabledState> = state.value
        .shouldBeInstanceOf<ComponentsUiState.Ready>()
        .data.all
        .map { it.enabledState }

    @Test
    fun `phase one runs on app change, enrichment waits for the route`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        val controller = backgroundScope.controller(loader)

        controller.state.value shouldBe ComponentsUiState.Loading

        controller.onAppChanged(appInfo())
        runCurrent()

        controller.state.value shouldBe ComponentsUiState.Ready(data)
        coVerify(exactly = 0) { loader.resolveEnabledStates(any()) }
    }

    @Test
    fun `a route that went active before phase one finished still enriches`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } coAnswers {
            gate.await()
            data
        }
        coEvery { loader.resolveEnabledStates(data) } returns mapOf(
            activity.key to false,
            service.key to true,
        )
        val controller = backgroundScope.controller(loader)

        controller.onComponentsRouteActive(true)
        controller.onAppChanged(appInfo())
        runCurrent()

        controller.state.value shouldBe ComponentsUiState.Loading

        gate.complete(Unit)
        runCurrent()

        val ready = controller.state.value.shouldBeInstanceOf<ComponentsUiState.Ready>()
        ready.data.activities.single().enabledState shouldBe ComponentEnabledState.DISABLED
        ready.data.services.single().enabledState shouldBe ComponentEnabledState.ENABLED
    }

    @Test
    fun `re-entering the route resolves the states again`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(data) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()
        controller.onComponentsRouteActive(false)
        runCurrent()
        controller.onComponentsRouteActive(true)
        runCurrent()

        coVerify(exactly = 1) { loader.load(any()) }
        coVerify(exactly = 2) { loader.resolveEnabledStates(data) }
    }

    @Test
    fun `leaving the route cancels an in-flight enrichment`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        var completed = 0
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(data) } coAnswers {
            started++
            gate.await()
            completed++
            mapOf(activity.key to false, service.key to false)
        }
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()

        started shouldBe 1
        controller.state.value shouldBe ComponentsUiState.Ready(data)

        controller.onComponentsRouteActive(false)
        runCurrent()

        // The pass was cancelled mid-flight and the last good state persists.
        completed shouldBe 0
        controller.state.value shouldBe ComponentsUiState.Ready(data)

        // A late completion of the cancelled call must not reach the state either.
        gate.complete(Unit)
        runCurrent()
        completed shouldBe 0
        controller.state.value shouldBe ComponentsUiState.Ready(data)

        // Re-entry starts a fresh pass, which now runs through.
        controller.onComponentsRouteActive(true)
        runCurrent()

        started shouldBe 2
        completed shouldBe 1
        controller.enabledStates() shouldBe listOf(
            ComponentEnabledState.DISABLED,
            ComponentEnabledState.DISABLED,
        )
    }

    /**
     * Enabling a disabled app changes neither its version code nor its update time, so the identity
     * is unchanged and phase 1 never re-runs. Only the route-entry re-resolve can correct the chips.
     */
    @Test
    fun `enabling the application clears the disabled state on route re-entry`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(data) } returnsMany listOf(
            mapOf(activity.key to false, service.key to false),
            mapOf(activity.key to true, service.key to true),
        )
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()

        controller.enabledStates() shouldBe listOf(
            ComponentEnabledState.DISABLED,
            ComponentEnabledState.DISABLED,
        )

        controller.onComponentsRouteActive(false)
        runCurrent()
        controller.onComponentsRouteActive(true)
        runCurrent()

        coVerify(exactly = 1) { loader.load(any()) }
        controller.enabledStates() shouldBe listOf(
            ComponentEnabledState.ENABLED,
            ComponentEnabledState.ENABLED,
        )
    }

    @Test
    fun `an identity change restarts both phases while the route stays active`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(data) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onComponentsRouteActive(true)
        controller.onAppChanged(appInfo(version = 1L))
        runCurrent()
        controller.onAppChanged(appInfo(version = 2L))
        runCurrent()

        coVerify(exactly = 2) { loader.load(any()) }
        coVerify(exactly = 2) { loader.resolveEnabledStates(data) }
    }

    @Test
    fun `a result from a superseded identity is never emitted`() = runTest {
        val firstData = ComponentsData(activities = listOf(activity))
        val secondData = ComponentsData(services = listOf(service))
        val gate = CompletableDeferred<Unit>()
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load("first") } coAnswers {
            gate.await()
            firstData
        }
        coEvery { loader.load("second") } returns secondData
        val controller = backgroundScope.controller(loader)

        val emissions = mutableListOf<ComponentsUiState>()
        val collector = controller.state.onEach { emissions += it }.launchIn(backgroundScope)
        runCurrent()

        controller.onAppChanged(appInfo(pkg = "first"))
        runCurrent()
        controller.onAppChanged(appInfo(pkg = "second"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        emissions.any { it is ComponentsUiState.Ready && it.data == firstData } shouldBe false
        emissions.last() shouldBe ComponentsUiState.Ready(secondData)
        collector.cancel()
    }

    @Test
    fun `the selection resolves against enriched data`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(data) } returns mapOf(activity.key to false)
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()
        controller.select(activity)
        runCurrent()

        controller.selectedComponent.value!!.enabledState shouldBe ComponentEnabledState.DISABLED
    }

    @Test
    fun `a selection whose key stops resolving is null`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns ComponentsData(activities = listOf(activity))
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()
        controller.select(service)
        runCurrent()

        controller.selectedComponent.value shouldBe null
    }

    @Test
    fun `dismissing clears the selection`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()
        controller.select(activity)
        runCurrent()
        controller.selectedComponent.value shouldBe activity

        controller.dismiss()
        runCurrent()
        controller.selectedComponent.value shouldBe null
    }

    @Test
    fun `losing the app clears state and selection`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()
        controller.select(activity)
        runCurrent()

        controller.onAppChanged(null)
        runCurrent()

        controller.state.value shouldBe ComponentsUiState.Loading
        controller.selectedComponent.value shouldBe null
    }

    @Test
    fun `leaving the route clears the selection`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()
        controller.select(activity)
        runCurrent()

        controller.onComponentsRouteActive(false)
        runCurrent()

        controller.selectedComponent.value shouldBe null
    }

    @Test
    fun `a long press enters multi-selection instead of opening the sheet`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()

        controller.onItemLongClick(activity)
        runCurrent()

        controller.selectedComponents.value shouldBe listOf(activity)
        controller.selectedComponent.value shouldBe null
    }

    @Test
    fun `a click extends an active selection and opens the sheet otherwise`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        runCurrent()

        controller.onItemClick(activity)
        runCurrent()
        controller.selectedComponent.value shouldBe activity
        controller.selectedComponents.value shouldBe emptyList()

        controller.dismiss()
        controller.onItemLongClick(activity)
        controller.onItemClick(service)
        runCurrent()
        controller.selectedComponents.value shouldBe listOf(activity, service)
        controller.selectedComponent.value shouldBe null

        // Clicking a selected entry removes it again.
        controller.onItemClick(activity)
        runCurrent()
        controller.selectedComponents.value shouldBe listOf(service)
    }

    @Test
    fun `losing the app clears the multi-selection`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        controller.onItemLongClick(activity)
        runCurrent()
        controller.selectedComponents.value shouldBe listOf(activity)

        controller.onAppChanged(null)
        runCurrent()

        controller.selectedComponents.value shouldBe emptyList()
    }

    @Test
    fun `leaving the route clears the multi-selection`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(any()) } returns emptyMap()
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        controller.onItemLongClick(activity)
        runCurrent()

        controller.onComponentsRouteActive(false)
        runCurrent()

        controller.selectedComponents.value shouldBe emptyList()
    }

    @Test
    fun `refresh re-resolves and the selection reflects the new states`() = runTest {
        val loader = mockk<AppComponentsLoader>()
        coEvery { loader.load(any()) } returns data
        coEvery { loader.resolveEnabledStates(data) } returnsMany listOf(
            mapOf(activity.key to true, service.key to true),
            mapOf(activity.key to false, service.key to true),
        )
        val controller = backgroundScope.controller(loader)

        controller.onAppChanged(appInfo())
        controller.onComponentsRouteActive(true)
        controller.onItemLongClick(activity)
        runCurrent()

        controller.selectedComponents.value.single().enabledState shouldBe ComponentEnabledState.ENABLED

        controller.refresh()
        runCurrent()

        coVerify(exactly = 1) { loader.load(any()) }
        coVerify(exactly = 2) { loader.resolveEnabledStates(data) }
        controller.selectedComponents.value.single().enabledState shouldBe ComponentEnabledState.DISABLED
    }
}
