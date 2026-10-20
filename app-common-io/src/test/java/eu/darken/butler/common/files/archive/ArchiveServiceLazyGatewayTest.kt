package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File

/**
 * GatewaySwitch -> ArchiveGateway -> ArchiveService -> Lazy<GatewaySwitch> is a cycle, so constructing the
 * service (and its format handlers) must never resolve the gateway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveServiceLazyGatewayTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()

    private var resolutions = 0
    private val throwingLazy = dagger.Lazy<GatewaySwitch> {
        resolutions++
        throw IllegalStateException("GatewaySwitch resolved")
    }

    @After
    fun teardown() {
        appScope.cancel()
    }

    @Test
    fun `construction, format detection and capabilities never resolve the gateway`() {
        val service = ArchiveService(
            dispatcherProvider = dispatcherProvider,
            gatewaySwitchLazy = throwingLazy,
            diskCache = ArchiveDiskCache(
                context = context,
                appScope = appScope,
                dispatcherProvider = dispatcherProvider,
            ),
            passwordStore = ArchivePasswordStore(),
        )

        val capabilities = ArchiveFormat.entries.map { format ->
            val container = LocalPath.build(File(context.cacheDir, "archive.${format.displayExtension}"))
            service.detectFormat(container) shouldBe format
            format.capabilities
        }

        capabilities.size shouldBe ArchiveFormat.entries.size
        resolutions shouldBe 0
    }
}
