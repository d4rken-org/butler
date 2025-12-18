package eu.darken.butler.common.files.saf

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class SAFGatewayTest : BaseTest() {

    @Test
    fun `init`() = runTest2(autoCancel = true) {
        val dispatcherProvider = TestDispatcherProvider()
        SAFGateway(
            fileSystemOps = mockk(),
            appScope = this,
            dispatcherProvider = dispatcherProvider
        )
    }
}