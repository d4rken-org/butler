package eu.darken.butler.workspace.core.appattribution

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.LocalPathNormalization
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.container.NormalPkg
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserManager2
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File

class AppAttributorTest : BaseTest() {

    private var originalPrimary = ""
    private var inventoryCollections = 0

    private val chrome: NormalPkg = mockk {
        every { id } returns Pkg.Id("com.android.chrome")
        every { packageName } returns "com.android.chrome"
        every { userHandle } returns UserHandle2(0)
    }

    @BeforeEach
    fun setup() {
        originalPrimary = LocalPathNormalization.primaryStorage
        LocalPathNormalization.primaryStorage = "/storage/emulated/0"
    }

    @AfterEach
    fun teardown() {
        LocalPathNormalization.primaryStorage = originalPrimary
    }

    private fun attributor(scope: CoroutineScope, data: Flow<PkgRepo.PkgData>) = AppAttributor(
        appScope = scope,
        dispatcherProvider = TestDispatcherProvider(),
        pkgRepo = mockk {
            every { this@mockk.data } returns data.onStart { inventoryCollections++ }
        },
        userManager = mockk {
            coEvery { currentUser() } returns mockk { every { handle } returns UserHandle2(0) }
        },
        documentUriResolver = mockk(),
    )

    private fun path(raw: String): APath<*> = LocalPath.build(File(raw))

    @Test
    fun `app folders get attributed, other folders don't`() = runTest {
        val attributor = attributor(backgroundScope, flowOf(PkgRepo.PkgData.from(listOf(chrome))))
        val candidates = mapOf(
            "chrome" to path("/storage/emulated/0/Android/data/com.android.chrome"),
            "gone" to path("/storage/emulated/0/Android/data/com.gone.app"),
            "download" to path("/storage/emulated/0/Download"),
        )

        attributor.attributionsFor(flowOf(candidates)).take(2).toList() shouldBe listOf(
            emptyMap(),
            mapOf(
                "chrome" to AppAttribution.App(chrome),
                "gone" to AppAttribution.Ghost("com.gone.app"),
            ),
        )
    }

    @Test
    fun `the package inventory is not loaded without app folders`() = runTest {
        val attributor = attributor(backgroundScope, flowOf(PkgRepo.PkgData.from(listOf(chrome))))
        val candidates = MutableStateFlow(mapOf("download" to path("/storage/emulated/0/Download")))

        attributor.attributionsFor(candidates).take(1).toList() shouldBe listOf(emptyMap())
        inventoryCollections shouldBe 0
    }

    @Test
    fun `a failed inventory attributes nothing until it loads`() = runTest {
        val data = MutableStateFlow(PkgRepo.PkgData(error = IllegalStateException()))
        val attributor = attributor(backgroundScope, data)
        val candidates = mapOf("gone" to path("/storage/emulated/0/Android/data/com.gone.app"))
        val emissions = mutableListOf<Map<String, AppAttribution>>()

        backgroundScope.launch { attributor.attributionsFor(flowOf(candidates)).toList(emissions) }
        runCurrent()
        emissions shouldBe listOf(emptyMap())

        data.value = PkgRepo.PkgData.from(listOf(chrome))
        runCurrent()
        emissions shouldBe listOf(emptyMap(), mapOf("gone" to AppAttribution.Ghost("com.gone.app")))
    }
}
