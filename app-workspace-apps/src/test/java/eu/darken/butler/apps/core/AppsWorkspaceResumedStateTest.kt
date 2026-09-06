package eu.darken.butler.apps.core

import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsEngine
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class AppsWorkspaceResumedStateTest : BaseTest() {

    private val id = Workspace.Id()
    private val viewPrefs = WorkspaceViewPrefs()
    private val tabViewStore = AppsTabViewStore(viewPrefs, SerializationIOModule().json())

    private inline fun <reified T> settingsValue(value: T) = mockk<DataStoreValue<T>>().apply {
        every { flow } returns flowOf(value)
    }

    private val app = mockk<AppItem>(relaxed = true)

    private val engineState = MutableStateFlow(
        AppsState(apps = listOf(app), filteredApps = listOf(app), isLoading = false),
    )

    private val storedStyle = AppsViewStyle(mode = AppsViewStyle.Mode.GRID)

    private val resumedArguments = AppsArguments.Default(
        filterConfig = TagFilterConfig(),
        sortSettings = SortSettings(),
        viewStyle = storedStyle,
    )

    private fun createWorkspace(dispatcher: CoroutineDispatcher, initDelayMs: Long): AppsWorkspace {
        val engine = mockk<AppsEngine>(relaxed = true).apply {
            every { state } returns engineState
            coEvery { updateFilterConfig(any()) } coAnswers {
                if (initDelayMs > 0) delay(initDelayMs) else yield()
            }
            coEvery { updateSortSettings(any()) } coAnswers { yield() }
        }
        return AppsWorkspace(
            id = id,
            creationArguments = resumedArguments,
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            appsEngineFactory = mockk<AppsEngine.Factory> { every { create(any(), any()) } returns engine },
            appsSettings = mockk<AppsSettings> {
                every { defaultFilterConfig } returns settingsValue(TagFilterConfig())
                every { defaultSortSettings } returns settingsValue(SortSettings())
                every { defaultViewStyle } returns settingsValue(AppsViewStyle.default())
            },
            tabViewStore = tabViewStore,
            appSizeCache = mockk(relaxed = true),
            pkgOps = mockk(relaxed = true),
            rootManager = mockk<RootManager> { every { useRoot } returns flowOf(false) },
            adbManager = mockk<AdbManager> { every { useAdb } returns flowOf(false) },
        )
    }

    private suspend fun AppsWorkspace.readyState(): AppsWorkspace.State.Ready {
        val current = state.first()
        current.shouldBeInstanceOf<AppsWorkspace.State.Ready>()
        return current
    }

    @Test
    fun `resumed tab, init wins the race`() = runTest {
        tabViewStore.setViewStyle(id, storedStyle)
        val workspace = createWorkspace(StandardTestDispatcher(testScheduler), initDelayMs = 0)
        advanceUntilIdle()
        workspace.readyState().apps shouldBe listOf(app)
    }

    @Test
    fun `resumed tab, combine wins the race`() = runTest {
        tabViewStore.setViewStyle(id, storedStyle)
        val workspace = createWorkspace(StandardTestDispatcher(testScheduler), initDelayMs = 1_000)
        advanceUntilIdle()
        workspace.readyState().apps shouldBe listOf(app)
    }

    @Test
    fun `fresh tab with no slot, combine wins the race`() = runTest {
        val workspace = createWorkspace(StandardTestDispatcher(testScheduler), initDelayMs = 1_000)
        advanceUntilIdle()
        workspace.readyState().apps shouldBe listOf(app)
    }
}
