package eu.darken.butler.upgrade.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The brand is spliced into the already-formatted translation, so the styled postfix has to land on
 * the right offsets no matter where the pattern put the placeholder.
 */
class BrandTitleSpliceTest : BaseTest() {

    private val brandColor = Color.Red

    // "Butler Pro" with the postfix (7..10) colored, like upgradeScreenTitle(upgraded = true).
    private val brand: AnnotatedString = buildAnnotatedString {
        append("Butler ")
        pushStyle(SpanStyle(color = brandColor))
        append("Pro")
        pop()
    }

    @Test
    fun `marker in the middle shifts the styled postfix by the prefix`() {
        val result = spliceBrandTitle("Get $BRAND_TITLE_MARKER", brand)

        result.text shouldBe "Get Butler Pro"
        result.spanStyles.size shouldBe 1
        result.spanStyles.single().item.color shouldBe brandColor
        result.spanStyles.single().start shouldBe 11
        result.spanStyles.single().end shouldBe 14
        result.text.substring(11, 14) shouldBe "Pro"
    }

    @Test
    fun `marker at the start keeps the postfix offsets inside the brand`() {
        val result = spliceBrandTitle("$BRAND_TITLE_MARKER holen", brand)

        result.text shouldBe "Butler Pro holen"
        result.spanStyles.size shouldBe 1
        result.spanStyles.single().start shouldBe 7
        result.spanStyles.single().end shouldBe 10
        result.text.substring(7, 10) shouldBe "Pro"
    }

    @Test
    fun `a duplicated marker renders the brand twice`() {
        val result = spliceBrandTitle("$BRAND_TITLE_MARKER und $BRAND_TITLE_MARKER", brand)

        result.text shouldBe "Butler Pro und Butler Pro"
        result.spanStyles.size shouldBe 2
        result.spanStyles[0].start shouldBe 7
        result.spanStyles[0].end shouldBe 10
        result.spanStyles[1].start shouldBe 22
        result.spanStyles[1].end shouldBe 25
        result.text.substring(22, 25) shouldBe "Pro"
    }

    @Test
    fun `a translation that lost the placeholder still shows the brand`() {
        val result = spliceBrandTitle("Get Pro", brand)

        result.text shouldBe "Get Pro Butler Pro"
        result.spanStyles.size shouldBe 1
        result.spanStyles.single().item.color shouldBe brandColor
        result.spanStyles.single().start shouldBe 15
        result.spanStyles.single().end shouldBe 18
    }
}
