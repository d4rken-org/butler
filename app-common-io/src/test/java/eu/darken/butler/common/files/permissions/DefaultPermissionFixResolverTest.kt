package eu.darken.butler.common.files.permissions

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.error.Fix
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException.Reason
import eu.darken.butler.common.root.RootManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.BaseTest

class DefaultPermissionFixResolverTest : BaseTest() {

    private fun resolver(rootAvailable: Boolean, adbAvailable: Boolean): DefaultPermissionFixResolver {
        val root = mockk<RootManager>().also { every { it.useRoot } returns flowOf(rootAvailable) }
        val adb = mockk<AdbManager>().also { every { it.useAdb } returns flowOf(adbAvailable) }
        // Unconfined dispatcher runs `launchIn` synchronously on construction, so the cached
        // booleans are populated by the time the resolver returns.
        return DefaultPermissionFixResolver(
            rootManager = root,
            adbManager = adb,
            appScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun pathError(reason: Reason) = PathPermissionDeniedException(
        path = LocalPath.build("/data/foo"),
        operation = "createFile",
        reason = reason,
    )

    @Test
    fun `NO_MECHANISM with no Root or ADB suggests ConfigureRootOrShizuku`() {
        resolver(rootAvailable = false, adbAvailable = false)
            .resolve(pathError(Reason.NO_MECHANISM)) shouldBe Fix.ConfigureRootOrShizuku
    }

    @Test
    fun `NO_MECHANISM with Root configured suggests no fix`() {
        resolver(rootAvailable = true, adbAvailable = false)
            .resolve(pathError(Reason.NO_MECHANISM)) shouldBe null
    }

    @Test
    fun `READONLY_FILESYSTEM never suggests a fix`() {
        resolver(rootAvailable = false, adbAvailable = false)
            .resolve(pathError(Reason.READONLY_FILESYSTEM)) shouldBe null
        resolver(rootAvailable = true, adbAvailable = false)
            .resolve(pathError(Reason.READONLY_FILESYSTEM)) shouldBe null
    }

    @Test
    fun `NOT_PERMITTED never suggests a fix`() {
        resolver(rootAvailable = false, adbAvailable = false)
            .resolve(pathError(Reason.NOT_PERMITTED)) shouldBe null
        resolver(rootAvailable = true, adbAvailable = true)
            .resolve(pathError(Reason.NOT_PERMITTED)) shouldBe null
    }

    @Test
    fun `ACCESS_DENIED with no Root or ADB suggests ConfigureRootOrShizuku`() {
        resolver(rootAvailable = false, adbAvailable = false)
            .resolve(pathError(Reason.ACCESS_DENIED)) shouldBe Fix.ConfigureRootOrShizuku
    }

    @Test
    fun `ACCESS_DENIED with ADB configured suggests no fix`() {
        resolver(rootAvailable = false, adbAvailable = true)
            .resolve(pathError(Reason.ACCESS_DENIED)) shouldBe null
    }

    @Test
    fun `non-permission Throwable returns null`() {
        resolver(rootAvailable = false, adbAvailable = false)
            .resolve(RuntimeException("oops")) shouldBe null
    }
}
