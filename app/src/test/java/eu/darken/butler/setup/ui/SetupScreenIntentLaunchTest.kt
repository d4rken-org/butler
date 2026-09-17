package eu.darken.butler.setup.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.flow.SingleEventFlow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import testhelpers.ComposeTest

/**
 * The install action puts an external URL on the permission intent route, which until then only
 * carried settings intents that always resolve. A device without an enabled browser has nothing to
 * handle it.
 */
class SetupScreenIntentLaunchTest : ComposeTest() {

    private val permissionIntents = MutableSharedFlow<Intent>(replay = 1)

    private val vm = mockk<SetupViewModel>(relaxed = true).apply {
        every { errorEvents } returns SingleEventFlow()
        every { navEvents } returns SingleEventFlow()
        every { permissionRequestEvents } returns permissionIntents
        every { runtimePermissionEvents } returns MutableSharedFlow()
        every { state } returns emptyFlow()
    }

    @Test
    fun `an install intent nothing can handle does not take the screen down`() {
        // Without this Robolectric swallows every startActivity, resolvable or not
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)

        permissionIntents.tryEmit(Intent(Intent.ACTION_VIEW, "https://porter.darken.eu/setup".toUri()))

        composeTestRule.setContent {
            PreviewWrapper {
                SetupScreenHost(vm = vm)
            }
        }

        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.onPermissionIntentFailed(ofType<ActivityNotFoundException>()) }
    }
}
