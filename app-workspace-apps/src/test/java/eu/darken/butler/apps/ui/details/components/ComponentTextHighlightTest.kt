package eu.darken.butler.apps.ui.details.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ComponentTextHighlightTest : BaseTest() {

    private val style = SpanStyle(fontWeight = FontWeight.SemiBold)

    @Test
    fun `a blank query produces no spans`() {
        "com.example.MainActivity".highlightMatches("", style).spanStyles shouldBe emptyList()
        "com.example.MainActivity".highlightMatches("  ", style).spanStyles shouldBe emptyList()
    }

    @Test
    fun `a single match produces one range`() {
        val result = "com.example.MainActivity".highlightMatches("Main", style)

        result.text shouldBe "com.example.MainActivity"
        result.spanStyles.map { it.start to it.end } shouldBe listOf(12 to 16)
        result.spanStyles.single().item shouldBe style
    }

    @Test
    fun `every occurrence is matched`() {
        val result = "com.example.example".highlightMatches("example", style)

        result.spanStyles.map { it.start to it.end } shouldBe listOf(4 to 11, 12 to 19)
    }

    @Test
    fun `matching ignores case`() {
        val result = "com.example.MainActivity".highlightMatches("mainactivity", style)

        result.spanStyles.map { it.start to it.end } shouldBe listOf(12 to 24)
    }

    @Test
    fun `adjacent matches terminate`() {
        val result = "aaaa".highlightMatches("aa", style)

        result.spanStyles.map { it.start to it.end } shouldBe listOf(0 to 2, 2 to 4)
    }

    @Test
    fun `a query that does not occur produces no spans`() {
        "com.example.MainActivity".highlightMatches("zzz", style).spanStyles shouldBe emptyList()
    }
}
