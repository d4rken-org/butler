package eu.darken.butler.common.compose

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import eu.darken.butler.upgrade.UpgradeRepo
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Instant

class ProGateTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val upgradeLabel = context.getString(R.string.general_upgrade_action)

    private fun info(
        isPro: Boolean,
        isSettled: Boolean = true,
        error: Throwable? = null,
    ) = object : UpgradeRepo.Info {
        override val type = UpgradeRepo.Type.GPLAY
        override val isPro = isPro
        override val isSettled = isSettled
        override val upgradedAt: Instant? = null
        override val error = error
    }

    @Test
    fun `pro users get the content untouched`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(isPro = true) { Text(text = CONTENT) }
            }
        }

        composeTestRule.onNodeWithText(CONTENT).assertIsDisplayed()
        composeTestRule.onNodeWithText(upgradeLabel).assertDoesNotExist()
    }

    @Test
    fun `gated content is unreachable behind the prompt`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(isPro = false, onUpgrade = {}) { Text(text = CONTENT) }
            }
        }

        // The content is still composed - it is what gets blurred - but clearAndSetSemantics takes
        // it out of the tree, so neither TalkBack nor a test can read the values off it.
        composeTestRule.onNodeWithText(CONTENT).assertDoesNotExist()
        composeTestRule.onNodeWithText(upgradeLabel).assertIsDisplayed()
    }

    @Test
    fun `the upgrade action fires`() {
        var upgrades = 0
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(isPro = false, onUpgrade = { upgrades++ }) { Text(text = CONTENT) }
            }
        }

        composeTestRule.onNodeWithText(upgradeLabel).performClick()
        upgrades shouldBe 1
    }

    @Test
    fun `the description is optional`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(isPro = false, description = DESCRIPTION, onUpgrade = {}) { Text(text = CONTENT) }
            }
        }

        composeTestRule.onNodeWithText(DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `without a navigation controller or callback there is no dead button`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(isPro = false) { Text(text = CONTENT) }
            }
        }

        composeTestRule.onNodeWithText(upgradeLabel).assertDoesNotExist()
    }

    @Test
    fun `the modifier applies in both states`() {
        var isPro by mutableStateOf(true)
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(modifier = Modifier.testTag(GATE_TAG), isPro = isPro) { Text(text = CONTENT) }
            }
        }

        composeTestRule.onNodeWithTag(GATE_TAG).assertIsDisplayed()

        isPro = false
        composeTestRule.onNodeWithTag(GATE_TAG).assertIsDisplayed()
    }

    @Test
    fun `content state survives an entitlement that resolves late`() {
        var isPro by mutableStateOf(true)
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(isPro = isPro) {
                    var taps by remember { mutableStateOf(0) }
                    Text(
                        modifier = Modifier.clickable { taps++ },
                        text = "$CONTENT $taps",
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("$CONTENT 0").performClick()
        composeTestRule.onNodeWithText("$CONTENT 1").assertIsDisplayed()

        // Billing settling to non-Pro must not restart the content it was wrapping all along.
        isPro = false
        isPro = true
        composeTestRule.onNodeWithText("$CONTENT 1").assertIsDisplayed()
    }

    @Test
    fun `taps cannot reach a gated control`() {
        var taps = 0
        composeTestRule.setContent {
            PreviewWrapper {
                ProGate(isPro = false, onUpgrade = {}) {
                    Text(
                        modifier = Modifier
                            .testTag(GATED_CONTROL_TAG)
                            .clickable { taps++ },
                        text = CONTENT,
                    )
                }
            }
        }

        // Cleared semantics take the control out of the tree entirely, so there is nothing left to
        // tap - and the scrim's pointer blocker covers whatever a coordinate tap would land on.
        composeTestRule.onNodeWithTag(GATED_CONTROL_TAG).assertDoesNotExist()
        taps shouldBe 0
    }

    @Test
    fun `only a settled purchase-free state renders as gated`() {
        info(isPro = true).rendersAsPro() shouldBe true
        info(isPro = false, isSettled = false).rendersAsPro() shouldBe true
        info(isPro = false, error = IllegalStateException("billing died")).rendersAsPro() shouldBe true
        null.rendersAsPro() shouldBe true

        info(isPro = false).rendersAsPro() shouldBe false
    }

    companion object {
        private const val CONTENT = "gated-content"
        private const val DESCRIPTION = "See how fast this ran."
        private const val GATE_TAG = "pro-gate"
        private const val GATED_CONTROL_TAG = "gated-control"
    }
}
