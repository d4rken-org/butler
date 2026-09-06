package eu.darken.butler.apps.ui.apps.items

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.R
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class AppListItemDensityTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Disabled is a tag, so this fixture also covers "tags survive compact". */
    private val item = AppsMockDataProvider.Presets.disabledAppItem.copy(
        appSize = AppsMockDataProvider.MockSizes.mb(84),
    )

    private fun rowAt(density: AppsViewStyle.Density) {
        composeTestRule.setContent {
            PreviewWrapper {
                AppListItem(
                    item = item,
                    density = density,
                    isSelected = false,
                    onClick = {},
                    onLongClick = {},
                )
            }
        }
    }

    @Test
    fun `a compact row drops the version`() {
        rowAt(AppsViewStyle.Density.COMPACT)

        composeTestRule.onNodeWithText("v${item.versionName}").assertDoesNotExist()
    }

    @Test
    fun `a compact row drops the size chip`() {
        rowAt(AppsViewStyle.Density.COMPACT)

        composeTestRule.onNodeWithTag(APP_SIZE_CHIP_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /** Tags carry actionable state, so they stay even where the size chip goes. */
    @Test
    fun `a compact row keeps the tags`() {
        rowAt(AppsViewStyle.Density.COMPACT)

        composeTestRule
            .onNodeWithText(context.getString(R.string.apps_tag_disabled_label))
            .assertIsDisplayed()
    }

    @Test
    fun `a comfortable row carries no install and update dates`() {
        rowAt(AppsViewStyle.Density.COMFORTABLE)

        composeTestRule
            .onAllNodes(hasText("Installed", substring = true))
            .fetchSemanticsNodes()
            .size shouldBe 0
    }

    @Test
    fun `a detailed row carries the install and update dates`() {
        rowAt(AppsViewStyle.Density.DETAILED)

        composeTestRule
            .onAllNodes(hasText("Installed", substring = true))
            .fetchSemanticsNodes()
            .size shouldBe 1
    }
}
