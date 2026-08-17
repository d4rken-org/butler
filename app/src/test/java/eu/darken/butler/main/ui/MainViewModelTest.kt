package eu.darken.butler.main.ui

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.storage.DocumentUriResolver
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * The state is combined from two booleans, so a positional slip compiles cleanly and silently
 * crosswires the cutout preference with the onboarding flag.
 */
class MainViewModelTest : BaseTest() {

    private lateinit var onboardingCompleted: MutableStateFlow<Boolean>
    private lateinit var displayCutoutAvoided: MutableStateFlow<Boolean>
    private lateinit var generalSettings: GeneralSettings

    private val upgradeRepo: UpgradeRepo = mockk(relaxed = true)
    private val workspaceRemote: WorkspaceRemote = mockk(relaxed = true)
    private val documentUriResolver: DocumentUriResolver = mockk(relaxed = true)
    private val operationFocusRequest: OperationFocusRequest = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        onboardingCompleted = MutableStateFlow(true)
        displayCutoutAvoided = MutableStateFlow(true)
        generalSettings = mockk {
            every { isOnboardingCompleted } returns dataStoreValue(onboardingCompleted)
            every { isDisplayCutoutAvoided } returns dataStoreValue(displayCutoutAvoided)
            every { themeMode } returns dataStoreValue(MutableStateFlow(ThemeMode.SYSTEM))
            every { themeStyle } returns dataStoreValue(MutableStateFlow(ThemeStyle.DEFAULT))
            every { themeColor } returns dataStoreValue(MutableStateFlow(ThemeColor.GREEN))
        }
    }

    private inline fun <reified T : Any> dataStoreValue(source: MutableStateFlow<T>): DataStoreValue<T> = mockk {
        every { flow } returns source
    }

    private fun createViewModel() = MainViewModel(
        context = mockk(relaxed = true),
        dispatcherProvider = TestDispatcherProvider(),
        upgradeRepo = upgradeRepo,
        generalSettings = generalSettings,
        workspaceRemote = workspaceRemote,
        json = Json,
        documentUriResolver = documentUriResolver,
        operationFocusRequest = operationFocusRequest,
        contentUriHelper = mockk(relaxed = true),
        externalOpenRouter = mockk(relaxed = true),
        pasteFileReader = mockk(relaxed = true),
    )

    @Test
    fun `avoidDisplayCutout follows the stored preference`() = runTest {
        displayCutoutAvoided.value = true
        createViewModel().state.filterNotNull().first().avoidDisplayCutout shouldBe true

        displayCutoutAvoided.value = false
        createViewModel().state.filterNotNull().first().avoidDisplayCutout shouldBe false
    }

    @Test
    fun `State defaults to drawing into the cutout`() {
        MainViewModel.State().avoidDisplayCutout shouldBe false
    }

    @Test
    fun `the cutout preference and the start screen stay independent`() = runTest {
        onboardingCompleted.value = true
        displayCutoutAvoided.value = false
        createViewModel().state.filterNotNull().first().let {
            it.startScreen shouldBe MainViewModel.State.StartScreen.HOME
            it.avoidDisplayCutout shouldBe false
        }

        onboardingCompleted.value = false
        displayCutoutAvoided.value = true
        createViewModel().state.filterNotNull().first().let {
            it.startScreen shouldBe MainViewModel.State.StartScreen.ONBOARDING
            it.avoidDisplayCutout shouldBe true
        }
    }
}
