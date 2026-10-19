package eu.darken.butler.workspace.core.appattribution

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.extensions.LocalPathNormalization
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.container.NormalPkg
import eu.darken.butler.common.storage.DocumentUriResolver
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppAttributorSafTest : BaseTest() {

    private val externalTree = "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata"
    private val otherTree = "content://com.termux.documents/tree/%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fhome"
    private var originalPrimary = ""

    private val chrome: NormalPkg = mockk {
        every { id } returns Pkg.Id("com.android.chrome")
        every { packageName } returns "com.android.chrome"
        every { userHandle } returns UserHandle2(0)
    }

    private val resolver: DocumentUriResolver = mockk {
        every { resolve(match { it.toString() == externalTree }) } returns
            LocalPath.build(File("/storage/emulated/0/Android/data"))
        every { resolve(match { it.toString() == otherTree }) } returns null
    }

    @Before
    fun setup() {
        originalPrimary = LocalPathNormalization.primaryStorage
        LocalPathNormalization.primaryStorage = "/storage/emulated/0"
    }

    @After
    fun teardown() {
        LocalPathNormalization.primaryStorage = originalPrimary
    }

    private fun attributor(scope: CoroutineScope, resolver: DocumentUriResolver = this.resolver) = AppAttributor(
        appScope = scope,
        dispatcherProvider = TestDispatcherProvider(),
        pkgRepo = mockk { every { data } returns flowOf(PkgRepo.PkgData.from(listOf(chrome))) },
        userManager = mockk {
            coEvery { currentUser() } returns mockk { every { handle } returns UserHandle2(0) }
        },
        documentUriResolver = resolver,
    )

    @Test
    fun `external storage trees resolve to their local app folders`() = runTest {
        val candidates = mapOf<String, APath<*>>(
            "chrome" to SAFPath.build(externalTree, "com.android.chrome"),
            "gone" to SAFPath.build(externalTree, "com.gone.app"),
        )
        val emissions = mutableListOf<Map<String, AppAttribution>>()

        backgroundScope.launch { attributor(backgroundScope).attributionsFor(flowOf(candidates)).toList(emissions) }
        runCurrent()

        emissions.last() shouldBe mapOf(
            "chrome" to AppAttribution.App(chrome),
            "gone" to AppAttribution.Ghost("com.gone.app"),
        )
    }

    @Test
    fun `an unresolvable tree is resolved once per pass and attributes nothing`() = runTest {
        val candidates = (1..20).associate { "f$it" to SAFPath.build(otherTree, "com.example.app$it") as APath<*> }
        val emissions = mutableListOf<Map<String, AppAttribution>>()

        backgroundScope.launch { attributor(backgroundScope).attributionsFor(flowOf(candidates)).toList(emissions) }
        runCurrent()

        emissions shouldBe listOf(emptyMap())
        verify(exactly = 1) { resolver.resolve(any()) }
    }

    @Test
    fun `folders that can't be package names never reach the resolver`() = runTest {
        val candidates = mapOf<String, APath<*>>("dcim" to SAFPath.build(otherTree, "DCIM"))

        backgroundScope.launch { attributor(backgroundScope).attributionsFor(flowOf(candidates)).toList(mutableListOf()) }
        runCurrent()

        verify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `a failed pass doesn't stop later ones`() = runTest {
        val throwing: DocumentUriResolver = mockk {
            every { resolve(any()) } throws IllegalStateException("boom") andThen
                LocalPath.build(File("/storage/emulated/0/Android/data"))
        }
        val candidates = MutableStateFlow(mapOf<String, APath<*>>("chrome" to SAFPath.build(externalTree, "com.android.chrome")))
        val emissions = mutableListOf<Map<String, AppAttribution>>()

        backgroundScope.launch {
            attributor(backgroundScope, throwing).attributionsFor(candidates).toList(emissions)
        }
        runCurrent()
        emissions shouldBe listOf(emptyMap())

        candidates.value = candidates.value + ("other" to LocalPath.build(File("/storage/emulated/0/Download")))
        runCurrent()
        emissions shouldBe listOf(emptyMap(), mapOf("chrome" to AppAttribution.App(chrome)))
    }
}
