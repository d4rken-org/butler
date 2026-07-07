package eu.darken.butler.explorer.ui.explorer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerSafLocationControllerTest : BaseTest() {

    private fun mockContext(): Context {
        val resolver = mockk<ContentResolver>().apply {
            every { takePersistableUriPermission(any(), any()) } just Runs
        }
        return mockk<Context>().apply {
            every { contentResolver } returns resolver
        }
    }

    private fun mockLocationManager(locationId: String = "loc-1"): SAFLocationManager =
        mockk<SAFLocationManager>().apply {
            coEvery { grantPermission(any()) } returns locationId
            coEvery { revokePermission(any()) } just Runs
            coEvery { setLocationLabel(any(), any()) } just Runs
        }

    private fun mockWorkspace(): ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
        coEvery { navigate(any()) } just Runs
    }

    private fun dialogs() = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private fun CoroutineScope.controller(
        context: Context = mockContext(),
        locationManager: SAFLocationManager = mockLocationManager(),
        dialogs: ExplorerDialogController = dialogs(),
        workspace: ExplorerWorkspace = mockWorkspace(),
        currentLocation: ExplorerLocation? = null,
        onError: (Throwable) -> Unit = {},
    ) = ExplorerSafLocationController(
        context = context,
        safLocationManager = locationManager,
        dialogs = dialogs,
        workspace = { workspace },
        currentLocation = { currentLocation },
        clearSelection = {},
        onError = onError,
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `sheet visibility toggles`() = runTest {
        val controller = controller()

        controller.showAddStorageSheet()
        controller.showAddStorageSheet.value shouldBe true

        controller.dismissAddStorageSheet()
        controller.showAddStorageSheet.value shouldBe false
    }

    @Test
    fun `picker result grants permission and asks for a label`() = runTest {
        val locationManager = mockLocationManager(locationId = "loc-42")
        val dialogs = dialogs()
        val controller = controller(locationManager = locationManager, dialogs = dialogs)

        controller.handleSAFPickerResult(mockk<Uri>())

        coVerify { locationManager.grantPermission(any()) }
        val dialog = dialogs.current().shouldBeInstanceOf<ExplorerDialogState.LocationStorageName>()
        dialog.locationId shouldBe "loc-42"
        dialog.currentName shouldBe null
    }

    @Test
    fun `picker result on device location triggers a refresh`() = runTest {
        val workspace = mockWorkspace()
        val controller = controller(
            workspace = workspace,
            currentLocation = mockk<ExplorerLocation.Device>(),
        )

        controller.handleSAFPickerResult(mockk<Uri>())

        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
    }

    @Test
    fun `picker failure is surfaced instead of thrown`() = runTest {
        var error: Throwable? = null
        val locationManager = mockk<SAFLocationManager>().apply {
            coEvery { grantPermission(any()) } throws IllegalStateException("nope")
        }
        val controller = controller(locationManager = locationManager, onError = { error = it })

        controller.handleSAFPickerResult(mockk<Uri>())

        error?.message shouldBe "nope"
    }

    @Test
    fun `location rename persists the trimmed label after confirming`() = runTest {
        val locationManager = mockLocationManager()
        val workspace = mockWorkspace()
        val dialogs = dialogs()
        val controller = controller(locationManager = locationManager, workspace = workspace, dialogs = dialogs)

        dialogs.show(ExplorerDialogState.LocationStorageName(locationId = "loc-9", currentName = "Old"))
        controller.onLocationStorageName("  New Name  ")
        advanceUntilIdle()

        coVerify { locationManager.setLocationLabel("loc-9", "New Name") }
        coVerify { workspace.navigate(ExplorerNavigation.Refresh) }
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `blank rename resets to the default label`() = runTest {
        val locationManager = mockLocationManager()
        val dialogs = dialogs()
        val controller = controller(locationManager = locationManager, dialogs = dialogs)

        dialogs.show(ExplorerDialogState.LocationStorageName(locationId = "loc-9", currentName = "Old"))
        controller.onLocationStorageName("   ")
        advanceUntilIdle()

        coVerify { locationManager.setLocationLabel("loc-9", null) }
    }

    @Test
    fun `rename without the matching dialog is ignored`() = runTest {
        val locationManager = mockLocationManager()
        val controller = controller(locationManager = locationManager)

        controller.onLocationStorageName("Name")
        runCurrent()

        coVerify(exactly = 0) { locationManager.setLocationLabel(any(), any()) }
    }
}
