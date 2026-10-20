package eu.darken.butler.common.pkgs

import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.sources.NormalPkgsSource
import eu.darken.butler.common.sharedresource.Resource
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.shell.ShellOps
import eu.darken.butler.common.shell.ShellOpsException
import eu.darken.butler.common.shell.ipc.ShellOpsCmd
import eu.darken.butler.common.user.UserHandle2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Package events reload the inventory on the app scope. A failing reload has to stay contained in
 * [PkgRepo.PkgData.error] instead of escaping to the scope's uncaught exception handling.
 */
class PkgRepoEventRefreshTest : BaseTest() {

    private val scopes = mutableListOf<CoroutineScope>()
    private val uncaught = mutableListOf<Throwable>()
    private val events = MutableSharedFlow<PackageEventListener.Event>()
    private var getPkgsCalls = 0

    private val pkgA: Installed = mockk<Installed>().also {
        every { it.id } returns Pkg.Id("com.test.a")
        every { it.userHandle } returns UserHandle2(0)
    }

    @AfterEach
    fun teardown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun shellFailure() = ShellOpsException(cmd = ShellOpsCmd("pm list packages -f --user 0"))

    private val ok: () -> Collection<Installed> = { listOf(pkgA) }
    private val failing: () -> Collection<Installed> = { throw shellFailure() }

    /** One entry per generation, the last one repeats. */
    private fun scriptedSource(vararg generations: () -> Collection<Installed>): NormalPkgsSource =
        mockk<NormalPkgsSource>().also {
            coEvery { it.getPkgs() } coAnswers {
                val generation = generations[minOf(getPkgsCalls, generations.size - 1)]
                getPkgsCalls++
                generation()
            }
        }

    private fun TestScope.createRepo(
        source: NormalPkgsSource,
        pkgOps: PkgOps = mockk(relaxed = true),
        gatewaySwitch: GatewaySwitch = mockk(relaxed = true),
        shellOps: ShellOps = mockk(relaxed = true),
    ) = PkgRepo(
        appScope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, t -> uncaught += t },
        ).also { scopes += it },
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        pkgEventListener = mockk<PackageEventListener>().also { every { it.events } returns events },
        pkgSources = setOf(source),
        gatewaySwitch = gatewaySwitch,
        pkgOps = pkgOps,
        shellOps = shellOps,
        userManager = mockk(relaxed = true),
    )

    /** Runs the initial generation and lets the event collector subscribe. */
    private suspend fun TestScope.start(repo: PkgRepo) {
        repo.data.first()
        advanceUntilIdle()
        events.subscriptionCount.value shouldBe 1
    }

    private suspend fun TestScope.emitEvent() {
        events.emit(PackageEventListener.Event.PackageInstalled(Pkg.Id("com.test")))
        advanceUntilIdle()
    }

    private fun Throwable?.shouldBeShellFailure() {
        shouldBeInstanceOf<ShellOpsException>()
        message shouldBe shellFailure().message
    }

    @Test
    fun `a failing event-triggered reload is stored instead of escaping`() = runTest {
        val repo = createRepo(scriptedSource(ok, failing))
        start(repo)
        repo.revision.value shouldBe 0L

        emitEvent()

        uncaught.shouldBeEmpty()
        repo.data.first().error.shouldBeShellFailure()
        repo.revision.value shouldBe 1L
    }

    @Test
    fun `events after a failed reload still trigger reloads`() = runTest {
        val repo = createRepo(scriptedSource(ok, failing))
        start(repo)

        emitEvent()
        emitEvent()

        getPkgsCalls shouldBe 3
        repo.revision.value shouldBe 2L
        uncaught.shouldBeEmpty()
    }

    @Test
    fun `a reload after a failed one recovers the packages`() = runTest {
        val repo = createRepo(scriptedSource(ok, failing, ok))
        start(repo)

        emitEvent()
        repo.data.first().error.shouldBeShellFailure()
        repo.revision.value shouldBe 1L

        emitEvent()
        repo.data.first().let {
            it.error.shouldBeNull()
            it.pkgs shouldContainExactly listOf(pkgA)
        }
        repo.revision.value shouldBe 2L
        uncaught.shouldBeEmpty()
    }

    @Test
    fun `a direct refresh still throws the failure`() = runTest {
        val repo = createRepo(scriptedSource(ok, failing))
        start(repo)

        shouldThrow<ShellOpsException> { repo.refresh() }
    }

    @Test
    fun `cancellation during an event-triggered reload stops the collector`() = runTest {
        val repo = createRepo(scriptedSource(ok, { throw CancellationException("sentinel") }, ok))
        start(repo)

        emitEvent()

        uncaught.shouldBeEmpty()
        events.subscriptionCount.value shouldBe 0

        emitEvent()
        getPkgsCalls shouldBe 2
    }

    @Test
    fun `a failing reload releases its leases`() = runTest {
        val resources = List(3) { mockk<Resource<Any>>(relaxed = true) }
        fun sharedResourceOf(resource: Resource<Any>) = mockk<SharedResource<Any>>().also {
            coEvery { it.get() } returns resource
        }

        val repo = createRepo(
            source = scriptedSource(failing),
            pkgOps = mockk<PkgOps>().also { every { it.sharedResource } returns sharedResourceOf(resources[0]) },
            gatewaySwitch = mockk<GatewaySwitch>().also {
                every { it.sharedResource } returns sharedResourceOf(resources[1])
            },
            shellOps = mockk<ShellOps>().also { every { it.sharedResource } returns sharedResourceOf(resources[2]) },
        )

        repo.data.first().error.shouldBeShellFailure()
        advanceUntilIdle()

        resources.forEach { verify(exactly = 1) { it.close() } }
    }
}
