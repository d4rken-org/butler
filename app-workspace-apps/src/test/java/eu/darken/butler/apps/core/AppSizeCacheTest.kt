package eu.darken.butler.apps.core

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.setup.core.SetupStateProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.util.UUID
import kotlin.uuid.toKotlinUuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSizeCacheTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val pkgOps = mockk<PkgOps>()
    private val pkgRevision = MutableStateFlow(0L)
    private val pkgRepo = mockk<PkgRepo>().also { every { it.revision } returns pkgRevision }
    private val setupStateProvider = mockk<SetupStateProvider>().also {
        every { it.state } returns flowOf(SetupStateProvider.State(modules = emptyMap()))
    }

    private val appScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setup() {
        mockkObject(Permission.PACKAGE_USAGE_STATS)
        every { Permission.PACKAGE_USAGE_STATS.isGranted(any()) } returns true
    }

    @After
    fun teardown() {
        appScopes.forEach { it.cancel() }
        appScopes.clear()
    }

    private fun installId(name: String) = InstallId(Pkg.Id(name), UserHandle2(0))

    private fun installed(name: String, uuid: UUID = UUID.randomUUID()): Installed {
        val info = ApplicationInfo().apply { storageUuid = uuid }
        return mockk<Installed>().also {
            every { it.installId } returns installId(name)
            every { it.applicationInfo } returns info
        }
    }

    private fun sizeStats(appBytes: Long, dataBytes: Long, cacheBytes: Long) = PkgOps.SizeStats(
        appBytes = appBytes,
        cacheBytes = cacheBytes,
        externalCacheBytes = null,
        dataBytes = dataBytes,
    )

    /**
     * A standalone scope on the test scheduler, not [TestScope.backgroundScope]: `advanceUntilIdle()`
     * does not drive background work (it only runs while the test coroutine itself suspends), so the
     * cache's `pkgRepo.revision` collector would never be scheduled. Not being a child of the test
     * job also keeps its never-completing collectors from stalling `runTest`; [teardown] cancels it.
     */
    private fun TestScope.createCache(): AppSizeCache {
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler)).also { appScopes += it }
        return AppSizeCache(
            appScope = appScope,
            context = context,
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            pkgRepo = pkgRepo,
            pkgOps = pkgOps,
            setupStateProvider = setupStateProvider,
        )
    }

    @Test
    fun `sizes are cached per install id`() = runTest {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns sizeStats(appBytes = 100, dataBytes = 50, cacheBytes = 20)
        val cache = createCache()

        cache.resolve(listOf(installed("com.a"), installed("com.b")))
        advanceUntilIdle()

        val snapshot = cache.snapshot.value
        snapshot.sizes.keys shouldBe setOf(installId("com.a"), installId("com.b"))
        snapshot.sizes.getValue(installId("com.a")) shouldBe AppSizeCache.AppSize(
            appBytes = 100,
            dataBytes = 30,
            cacheBytes = 20,
        )
        snapshot.sizes.getValue(installId("com.a")).total shouldBe 150
    }

    @Test
    fun `a second resolve does not query again`() = runTest {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns sizeStats(appBytes = 100, dataBytes = 50, cacheBytes = 20)
        val cache = createCache()
        val pkg = installed("com.a")

        cache.resolve(listOf(pkg))
        cache.resolve(listOf(pkg))
        advanceUntilIdle()

        coVerify(exactly = 1) { pkgOps.querySizeStats(installId("com.a"), any()) }
    }

    @Test
    fun `a package refresh clears everything`() = runTest {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns sizeStats(appBytes = 100, dataBytes = 50, cacheBytes = 20)
        val cache = createCache()

        cache.resolve(listOf(installed("com.a")))
        advanceUntilIdle()
        cache.snapshot.value.sizes.size shouldBe 1

        pkgRevision.value = 1L
        advanceUntilIdle()

        cache.snapshot.value.sizes shouldBe emptyMap()
        cache.snapshot.value.attempted shouldBe emptySet()
    }

    @Test
    fun `invalidate drops only the given ids and re-measures them`() = runTest {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns sizeStats(appBytes = 100, dataBytes = 50, cacheBytes = 20)
        val cache = createCache()
        val a = installed("com.a")
        val b = installed("com.b")

        cache.resolve(listOf(a, b))
        advanceUntilIdle()

        cache.invalidate(listOf(installId("com.a")))
        cache.snapshot.value.sizes.keys shouldBe setOf(installId("com.b"))

        cache.resolve(listOf(a, b))
        advanceUntilIdle()

        cache.snapshot.value.sizes.keys shouldBe setOf(installId("com.a"), installId("com.b"))
        coVerify(exactly = 2) { pkgOps.querySizeStats(installId("com.a"), any()) }
        coVerify(exactly = 1) { pkgOps.querySizeStats(installId("com.b"), any()) }
    }

    @Test
    fun `nothing is queried without usage access`() = runTest {
        every { Permission.PACKAGE_USAGE_STATS.isGranted(any()) } returns false
        coEvery { pkgOps.querySizeStats(any(), any()) } returns sizeStats(appBytes = 100, dataBytes = 50, cacheBytes = 20)
        val cache = createCache()

        cache.resolve(listOf(installed("com.a")))
        advanceUntilIdle()

        cache.snapshot.value.sizes shouldBe emptyMap()
        coVerify(exactly = 0) { pkgOps.querySizeStats(any(), any()) }
    }

    @Test
    fun `availability flips once access is granted`() = runTest {
        every { Permission.PACKAGE_USAGE_STATS.isGranted(any()) } returns false
        val cache = createCache()
        cache.isAvailable.value shouldBe false

        every { Permission.PACKAGE_USAGE_STATS.isGranted(any()) } returns true
        cache.refreshAvailability()

        cache.isAvailable.value shouldBe true
    }

    @Test
    fun `a batch is discarded when the revision moves before publication`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { pkgOps.querySizeStats(any(), any()) } coAnswers {
            gate.await()
            sizeStats(appBytes = 100, dataBytes = 50, cacheBytes = 20)
        }
        val cache = createCache()

        val resolving = launch { cache.resolve(listOf(installed("com.a"))) }
        advanceUntilIdle()

        // Lands while the query is in flight, i.e. inside the check-then-publish window.
        cache.invalidateAll()
        gate.complete(Unit)
        resolving.join()
        advanceUntilIdle()

        cache.snapshot.value.sizes shouldBe emptyMap()
        cache.snapshot.value.attempted shouldBe emptySet()
    }

    @Test
    fun `a failed query still counts as measured`() = runTest {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns null
        val cache = createCache()
        val pkg = installed("com.a")

        cache.resolve(listOf(pkg))
        advanceUntilIdle()

        cache.snapshot.value.sizes shouldBe emptyMap()
        cache.snapshot.value.attempted shouldBe setOf(installId("com.a"))

        cache.resolve(listOf(pkg))
        advanceUntilIdle()

        coVerify(exactly = 1) { pkgOps.querySizeStats(installId("com.a"), any()) }
    }

    @Test
    fun `the app's own storage uuid is passed through`() = runTest {
        coEvery { pkgOps.querySizeStats(any(), any()) } returns sizeStats(appBytes = 100, dataBytes = 50, cacheBytes = 20)
        val uuid = UUID.randomUUID()
        val cache = createCache()

        cache.resolve(listOf(installed("com.a", uuid = uuid)))
        advanceUntilIdle()

        coVerify { pkgOps.querySizeStats(installId("com.a"), uuid.toKotlinUuid()) }
    }
}
