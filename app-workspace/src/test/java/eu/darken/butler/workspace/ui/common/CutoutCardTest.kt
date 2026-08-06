package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The toolbar cards force a min height so the card matches the workspace button, which leaves
 * slack when the content is shorter (e.g. the collapsed Explorer breadcrumb row). That slack has
 * to be split evenly, not dumped below the content.
 */
class CutoutCardTest : ComposeTest() {

    private val cardTag = "card"
    private val contentTag = "content"
    private val cutoutTag = "cutout"

    @Test
    fun `content is vertically centered when a min height is enforced without a cutout`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CutoutCard(
                    modifier = Modifier
                        .requiredHeightIn(min = 40.dp)
                        .testTag(cardTag),
                    cutoutContent = null,
                    contentPadding = CutoutCardDefaults.contentPadding(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .testTag(contentTag),
                    )
                }
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(contentTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe 40.dp
        (content.top - card.top) shouldBe (card.bottom - content.bottom)
    }

    @Test
    fun `card without a cutout wraps its content width by default`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.width(300.dp)) {
                    CutoutCard(
                        modifier = Modifier.testTag(cardTag),
                        cutoutContent = null,
                        contentPadding = CutoutCardDefaults.contentPadding(6.dp),
                    ) {
                        Box(modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.right - card.left) shouldBe 32.dp
    }

    @Test
    fun `card without a cutout honors an enforced width`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.width(300.dp)) {
                    CutoutCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(cardTag),
                        cutoutContent = null,
                        contentPadding = CutoutCardDefaults.contentPadding(6.dp),
                    ) {
                        Box(modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.right - card.left) shouldBe 300.dp
    }

    @Test
    fun `content is vertically centered when a min height is enforced with a full height cutout`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CutoutCard(
                    modifier = Modifier
                        .requiredHeightIn(min = 40.dp)
                        .testTag(cardTag),
                    cutoutContent = { Box(modifier = Modifier.size(40.dp)) },
                    cutoutMode = CutoutMode.FullHeight,
                    contentPadding = CutoutCardDefaults.contentPadding(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .testTag(contentTag),
                    )
                }
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(contentTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe 40.dp
        (content.top - card.top) shouldBe (card.bottom - content.bottom)
    }

    @Test
    fun `cutout content is top aligned by default`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CutoutCard(
                    modifier = Modifier.testTag(cardTag),
                    cutoutContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag(cutoutTag),
                        )
                    },
                    cutoutMode = CutoutMode.FullHeight,
                    contentPadding = CutoutCardDefaults.contentPadding(6.dp),
                ) {
                    Box(modifier = Modifier.size(80.dp))
                }
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()
        val cutout = composeTestRule.onNodeWithTag(cutoutTag).getUnclippedBoundsInRoot()

        cutout.top shouldBe card.top
    }

    @Test
    fun `cutout content can be vertically centered`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CutoutCard(
                    modifier = Modifier.testTag(cardTag),
                    cutoutContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag(cutoutTag),
                        )
                    },
                    cutoutMode = CutoutMode.FullHeight,
                    cutoutAlignment = Alignment.CenterVertically,
                    contentPadding = CutoutCardDefaults.contentPadding(6.dp),
                ) {
                    Box(modifier = Modifier.size(80.dp))
                }
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()
        val cutout = composeTestRule.onNodeWithTag(cutoutTag).getUnclippedBoundsInRoot()

        (cutout.top - card.top) shouldBe (card.bottom - cutout.bottom)
    }
}
