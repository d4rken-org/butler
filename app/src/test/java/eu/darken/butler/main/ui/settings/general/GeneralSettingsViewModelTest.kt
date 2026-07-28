package eu.darken.butler.main.ui.settings.general

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.locale.LocaleManager
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.motd.MotdSettings
import eu.darken.butler.provider.documents.core.DocumentsProviderSettings
import eu.darken.butler.upgrade.UpgradeRepo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * The screen state is combined from a row of booleans, so a positional slip compiles cleanly and
 * silently shows or writes the wrong preference.
 */
class GeneralSettingsViewModelTest : BaseTest() {

    private lateinit var updateCheckEnabled: MutableStateFlow<Boolean>
    private lateinit var confirmExitEnabled: MutableStateFlow<Boolean>
    private lateinit var displayCutoutAvoided: MutableStateFlow<Boolean>
    private lateinit var motdEnabled: MutableStateFlow<Boolean>
    private lateinit var documentsProviderEnabled: MutableStateFlow<Boolean>

    private lateinit var updateCheckValue: DataStoreValue<Boolean>
    private lateinit var confirmExitValue: DataStoreValue<Boolean>
    private lateinit var displayCutoutValue: DataStoreValue<Boolean>
    private lateinit var motdValue: DataStoreValue<Boolean>
    private lateinit var documentsProviderValue: DataStoreValue<Boolean>

    private lateinit var generalSettings: GeneralSettings
    private lateinit var motdSettings: MotdSettings
    private lateinit var documentsProviderSettings: DocumentsProviderSettings

    private val localeManager: LocaleManager = mockk(relaxed = true)
    private lateinit var upgradeRepo: UpgradeRepo

    @BeforeEach
    fun setup() {
        val info: UpgradeRepo.Info = mockk { every { isPro } returns false }
        upgradeRepo = mockk { every { upgradeInfo } returns flowOf(info) }

        updateCheckEnabled = MutableStateFlow(true)
        confirmExitEnabled = MutableStateFlow(true)
        displayCutoutAvoided = MutableStateFlow(true)
        motdEnabled = MutableStateFlow(true)
        documentsProviderEnabled = MutableStateFlow(true)

        updateCheckValue = booleanValue(updateCheckEnabled)
        confirmExitValue = booleanValue(confirmExitEnabled)
        displayCutoutValue = booleanValue(displayCutoutAvoided)
        motdValue = booleanValue(motdEnabled)
        documentsProviderValue = booleanValue(documentsProviderEnabled)

        generalSettings = mockk {
            every { themeMode } returns mockk { every { flow } returns MutableStateFlow(ThemeMode.SYSTEM) }
            every { themeStyle } returns mockk { every { flow } returns MutableStateFlow(ThemeStyle.DEFAULT) }
            every { themeColor } returns mockk { every { flow } returns MutableStateFlow(ThemeColor.GREEN) }
            every { isUpdateCheckEnabled } returns updateCheckValue
            every { isConfirmExitEnabled } returns confirmExitValue
            every { isDisplayCutoutAvoided } returns displayCutoutValue
        }
        motdSettings = mockk { every { isMotdEnabled } returns motdValue }
        documentsProviderSettings = mockk { every { isEnabled } returns documentsProviderValue }
    }

    private fun booleanValue(source: MutableStateFlow<Boolean>): DataStoreValue<Boolean> = mockk {
        every { flow } returns source
        coEvery { update(any()) } returns DataStoreValue.Updated(old = true, new = false)
    }

    private fun createViewModel() = GeneralSettingsViewModel(
        dispatcherProvider = TestDispatcherProvider(),
        generalSettings = generalSettings,
        localeManager = localeManager,
        motdSettings = motdSettings,
        documentsProviderSettings = documentsProviderSettings,
        upgradeRepo = upgradeRepo,
    )

    @Test
    fun `avoidDisplayCutout reflects the stored preference`() = runTest {
        displayCutoutAvoided.value = true
        createViewModel().state.filterNotNull().first().avoidDisplayCutout shouldBe true

        displayCutoutAvoided.value = false
        createViewModel().state.filterNotNull().first().let {
            it.avoidDisplayCutout shouldBe false
            // ...without dragging the neighboring switches along
            it.confirmExitEnabled shouldBe true
            it.updateCheckEnabled shouldBe true
        }
    }

    @Test
    fun `State defaults the switch to off`() {
        GeneralSettingsViewModel.State().avoidDisplayCutout shouldBe false
    }

    @Test
    fun `updateAvoidDisplayCutout writes only the cutout preference`() = runTest {
        createViewModel().updateAvoidDisplayCutout(false)

        val written = slot<(Boolean) -> Boolean?>()
        coVerify { displayCutoutValue.update(capture(written)) }
        written.captured(true) shouldBe false

        coVerify(exactly = 0) { confirmExitValue.update(any()) }
        coVerify(exactly = 0) { updateCheckValue.update(any()) }
        coVerify(exactly = 0) { motdValue.update(any()) }
        coVerify(exactly = 0) { documentsProviderValue.update(any()) }
    }
}
