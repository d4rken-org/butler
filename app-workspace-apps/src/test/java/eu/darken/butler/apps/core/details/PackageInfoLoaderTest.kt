package eu.darken.butler.apps.core.details

import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PackageInfoLoaderTest : BaseTest() {

    private val ready = PackageInfoState.Ready(ApkArchiveInfo(id = "com.example.app".toPkgId()))

    private fun appInfo(
        pkg: String = "com.example.app",
        version: Long = 1L,
    ) = AppInfo(
        install = mockk<Installed> {
            every { packageName } returns pkg
            every { versionCode } returns version
            every { installId } returns InstallId(pkg.toPkgId(), UserHandle2(0))
        },
    )

    /** The state flow is lazily shared, so a collector is what turns the loader on. */
    private fun CoroutineScope.loader(
        appInfo: Flow<AppInfo?>,
        load: suspend (AppInfo) -> PackageInfoState,
    ) = PackageInfoLoader(scope = this, appInfo = appInfo, load = load).also { it.state.launchIn(this) }

    @Test
    fun `nothing is inspected before the route is entered`() = runTest {
        var loads = 0
        val loader = backgroundScope.loader(MutableStateFlow(appInfo())) {
            loads++
            ready
        }
        runCurrent()

        loads shouldBe 0
        loader.state.value shouldBe PackageInfoState.Loading
    }

    @Test
    fun `each entry into the route runs exactly one load`() = runTest {
        var loads = 0
        val loader = backgroundScope.loader(MutableStateFlow(appInfo())) {
            loads++
            ready
        }

        loader.onRequested()
        runCurrent()
        loads shouldBe 1
        loader.state.value shouldBe ready

        loader.onRequested()
        runCurrent()
        loads shouldBe 2
    }

    @Test
    fun `an app update re-runs the load, an unchanged app does not`() = runTest {
        var loads = 0
        val apps = MutableStateFlow(appInfo(version = 1L))
        val loader = backgroundScope.loader(apps) {
            loads++
            ready
        }

        loader.onRequested()
        runCurrent()
        loads shouldBe 1

        // Same identity, fresh instance: the package data flow re-emits on unrelated changes.
        apps.value = appInfo(version = 1L)
        runCurrent()
        loads shouldBe 1

        apps.value = appInfo(version = 2L)
        runCurrent()
        loads shouldBe 2
    }

    /** Nothing caches a failure, so re-entering the route is the retry. */
    @Test
    fun `re-entering the route retries after Unavailable`() = runTest {
        var result: PackageInfoState = PackageInfoState.Unavailable
        val loader = backgroundScope.loader(MutableStateFlow(appInfo())) { result }

        loader.onRequested()
        runCurrent()
        loader.state.value shouldBe PackageInfoState.Unavailable

        result = ready
        loader.onRequested()
        runCurrent()
        loader.state.value shouldBe ready
    }

    @Test
    fun `a re-entry cancels the load that is still running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        var attempts = 0
        val loader = backgroundScope.loader(MutableStateFlow(appInfo())) {
            val attempt = ++attempts
            started += attempt
            gate.await()
            finished += attempt
            ready
        }

        loader.onRequested()
        runCurrent()
        loader.onRequested()
        runCurrent()

        started shouldBe listOf(1, 2)

        gate.complete(Unit)
        runCurrent()

        finished shouldBe listOf(2)
        loader.state.value shouldBe ready
    }
}
