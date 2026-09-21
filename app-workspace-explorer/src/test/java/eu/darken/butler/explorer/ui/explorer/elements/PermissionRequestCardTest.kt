package eu.darken.butler.explorer.ui.explorer.elements

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.permissions.core.SAFPickerGrant
import eu.darken.butler.setup.core.SetupModule
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The body line and the "Full access" option card pick their wording from the same requirements, so
 * they must never name different access mechanisms. Asserted per requirement shape through string
 * ids, which leaves a copy edit passing and a wrong branch failing.
 */
@Config(qualifiers = "w400dp-h1600dp")
class PermissionRequestCardTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val safPickerGrant = SAFPickerGrant(
        intent = Intent(),
        targetPath = LocalPath.build("/storage/emulated/0/Android/data"),
    )

    private fun setCard(requirements: PathRequirements) {
        composeTestRule.setContent {
            PreviewWrapper {
                PermissionRequestCard(
                    setupRequirements = requirements,
                    onNavigateToSetup = {},
                    onLaunchSAFPicker = if (requirements.safPickerGrant != null) ({ }) else null,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertShown(stringId: Int) {
        composeTestRule.onNodeWithText(context.getString(stringId)).assertIsDisplayed()
    }

    private fun assertAbsent(stringId: Int) {
        composeTestRule.onAllNodes(hasText(context.getString(stringId))).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `a Root only folder offers Root in the full access card`() {
        setCard(PathRequirements(combos = setOf(setOf(SetupModule.Type.ROOT))))

        assertShown(R.string.explorer_permission_option_setup_root_description)
        assertAbsent(R.string.explorer_permission_option_setup_description)
    }

    @Test
    fun `a Root only folder explains Root in the body`() {
        setCard(PathRequirements(combos = setOf(setOf(SetupModule.Type.ROOT))))

        assertShown(R.string.explorer_permission_setup_root_description)
    }

    @Test
    fun `a Shizuku only folder offers Shizuku in the full access card`() {
        setCard(PathRequirements(combos = setOf(setOf(SetupModule.Type.SHIZUKU))))

        assertShown(R.string.explorer_permission_option_setup_shizuku_description)
        assertShown(R.string.explorer_permission_setup_shizuku_description)
    }

    @Test
    fun `a folder reachable either way offers both in the full access card`() {
        setCard(
            PathRequirements(
                combos = setOf(
                    setOf(SetupModule.Type.ROOT),
                    setOf(SetupModule.Type.SHIZUKU),
                ),
            ),
        )

        assertShown(R.string.explorer_permission_option_setup_description)
        assertShown(R.string.explorer_permission_setup_both_description)
    }

    @Test
    fun `a Root only folder with a picker alternative explains Root in the body`() {
        setCard(
            PathRequirements(
                combos = setOf(setOf(SetupModule.Type.ROOT)),
                safPickerGrant = safPickerGrant,
            ),
        )

        assertShown(R.string.explorer_permission_multiple_options_root_description)
        assertAbsent(R.string.explorer_permission_multiple_options_description)
    }

    @Test
    fun `a Root only folder with a picker alternative offers Root in the full access card`() {
        setCard(
            PathRequirements(
                combos = setOf(setOf(SetupModule.Type.ROOT)),
                safPickerGrant = safPickerGrant,
            ),
        )

        assertShown(R.string.explorer_permission_option_setup_root_description)
    }
}
