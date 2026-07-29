package eu.darken.butler.common.pkgs

import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.sources.NormalPkgsSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * [PkgRepo.revision] is what tells consumers their derived, per-package data is stale. A refresh
 * whose caller goes away still reloads and publishes, so the revision has to advance with it.
 */
class PkgRepoRevisionTest : BaseTest() {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun teardown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun TestScope.newScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler)).also { scopes += it }

    /**
     * The first generation (triggered by `refresh()`'s own `cache.value()`) has to get through, the
     * second one - the actual reload - is the one we hold open while the caller is cancelled.
     */
    private fun gatedSource(gate: CompletableDeferred<Unit>): NormalPkgsSource {
        var generations = 0
        return mockk<NormalPkgsSource>().also {
            coEvery { it.getPkgs() } coAnswers {
                generations++
                if (generations >= 2) gate.await()
                emptyList<Installed>()
            }
        }
    }

    private fun TestScope.createRepo(source: NormalPkgsSource) = PkgRepo(
        appScope = newScope(),
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        pkgEventListener = mockk<PackageEventListener>().also { every { it.events } returns emptyFlow() },
        pkgSources = setOf(source),
        gatewaySwitch = mockk(relaxed = true),
        pkgOps = mockk(relaxed = true),
        shellOps = mockk(relaxed = true),
        userManager = mockk(relaxed = true),
    )

    @Test
    fun `the revision advances when the refresh completes`() = runTest {
        val repo = createRepo(gatedSource(CompletableDeferred(Unit)))
        repo.revision.value shouldBe 0L

        val refreshing = launch { repo.refresh() }
        advanceUntilIdle()
        refreshing.join()

        repo.revision.value shouldBe 1L
    }

    @Test
    fun `the revision advances even when the refresh caller is cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = createRepo(gatedSource(gate))
        repo.revision.value shouldBe 0L

        val refreshing = launch { repo.refresh() }
        advanceUntilIdle()
        // The reload is under way but hasn't published yet.
        repo.revision.value shouldBe 0L

        refreshing.cancel()
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        repo.revision.value shouldBe 1L
    }
}
