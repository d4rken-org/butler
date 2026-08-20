package eu.darken.butler.main.ui

import android.net.Uri
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.external.ExternalOpenOption
import eu.darken.butler.main.core.external.ExternalOpenRouter
import eu.darken.butler.main.core.external.SourceRef
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Single-file shares now go through the arrival dialog instead of straight to the Saver, so what
 * the Saver used to receive directly has to survive the detour.
 *
 * Robolectric because the arrival is built around a `content://` [Uri].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelArrivalTest : BaseTest() {

    private val uri = Uri.parse("content://com.example.files/document/42")
    private val callerPackage = "com.example.files"

    private val workspaceRemote = mockk<WorkspaceRemote>(relaxed = true)
    private val contentUriHelper = mockk<ContentUriHelper>()
    private val externalOpenRouter = mockk<ExternalOpenRouter>()
    private lateinit var generalSettings: GeneralSettings

    @Before
    fun setup() {
        generalSettings = mockk {
            every { isOnboardingCompleted } returns dataStoreValue(MutableStateFlow(true))
            every { isDisplayCutoutAvoided } returns dataStoreValue(MutableStateFlow(false))
            every { themeMode } returns dataStoreValue(MutableStateFlow(ThemeMode.SYSTEM))
            every { themeStyle } returns dataStoreValue(MutableStateFlow(ThemeStyle.DEFAULT))
            every { themeColor } returns dataStoreValue(MutableStateFlow(ThemeColor.GREEN))
        }
        every { contentUriHelper.extractInfo(uri) } returns ContentUriHelper.SourceInfo(
            uri = uri,
            displayName = "backup.zip",
            size = 4096L,
            mimeType = "application/zip",
            isAccessible = true,
        )
        every { externalOpenRouter.sanitize(uri) } returns SourceRef.Content(uri)
        every { externalOpenRouter.resolveMime(any(), any(), any()) } returns MimeInfo("application/zip")
        every { externalOpenRouter.resolveLocation(any()) } returns null
        coEvery { workspaceRemote.execute(any()) } returns
            WorkspaceAction.Create.Result.Success(Workspace.Id())
    }

    private inline fun <reified T : Any> dataStoreValue(source: MutableStateFlow<T>): DataStoreValue<T> = mockk {
        every { flow } returns source
    }

    private fun createViewModel() = MainViewModel(
        context = mockk(relaxed = true),
        dispatcherProvider = TestDispatcherProvider(),
        upgradeRepo = mockk(relaxed = true),
        generalSettings = generalSettings,
        workspaceRemote = workspaceRemote,
        json = Json,
        documentUriResolver = mockk(relaxed = true),
        operationFocusRequest = mockk(relaxed = true),
        contentUriHelper = contentUriHelper,
        externalOpenRouter = externalOpenRouter,
        pasteFileReader = mockk(relaxed = true),
    )

    private suspend fun createArguments(block: suspend (MainViewModel) -> Unit): SaverArguments.Default {
        val action = slot<WorkspaceAction>()
        coEvery { workspaceRemote.execute(capture(action)) } returns
            WorkspaceAction.Create.Result.Success(Workspace.Id())
        block(createViewModel())
        return action.captured.shouldBeInstanceOf<WorkspaceAction.Create>()
            .arguments.shouldBeInstanceOf<SaverArguments.Default>()
    }

    @Test
    fun `an arrival carries the caption and the caller`() = runTest {
        val vm = createViewModel()

        vm.onExternalFile(uri, "application/zip", callerPackage, caption = "look at this")

        val arrival = vm.externalOpen.filterNotNull().first()
        arrival.caption shouldBe "look at this"
        arrival.callerPackage shouldBe callerPackage
        arrival.displayName shouldBe "backup.zip"
        arrival.options shouldBe listOf(ExternalOpenOption.VIEW, ExternalOpenOption.SAVE_AS)
    }

    @Test
    fun `saving from the dialog reaches the Saver with what the direct share used to pass`() = runTest {
        val direct = createArguments { vm ->
            vm.createSaverWorkspace(sourceUris = listOf(uri), callerPackage = callerPackage)
        }

        val viaDialog = createArguments { vm ->
            vm.onExternalFile(uri, "application/zip", callerPackage, caption = "look at this")
            vm.externalOpen.filterNotNull().first()
            vm.onExternalOpenAction(ExternalOpenOption.SAVE_AS)
        }

        viaDialog.sourceUris shouldBe direct.sourceUris
        viaDialog.callerPackage shouldBe direct.callerPackage
        viaDialog.sourceUris shouldBe listOf(uri.toString())
        viaDialog.callerPackage shouldBe callerPackage.toPkgId()
    }
}
