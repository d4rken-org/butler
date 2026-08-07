package eu.darken.butler.common.compose

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Resolves the real flavor resources rather than a sample pattern, so this also proves the two
 * markers survive Android's format path and never reach the user.
 *
 * Flavor-agnostic on purpose: it asserts against whatever this variant's qualifier resource says
 * ("Pro" on GPLAY, "FOSS" on FOSS) so the one test guards both. The resources are flavor-owned, so
 * a variant that compiles proves nothing about the other.
 */
class BrandTitleTest : ComposeTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val name: String
        get() = context.getString(R.string.app_name)

    private val qualifier: String
        get() = context.getString(R.string.app_name_upgrade_postfix)

    private val composed: String
        get() = context.getString(R.string.app_name_upgraded_template, name, qualifier)

    private var primary = Color.Unspecified
    private var tertiary = Color.Unspecified

    private fun capture(block: @Composable () -> AnnotatedString): AnnotatedString {
        lateinit var captured: AnnotatedString
        composeTestRule.setContent {
            PreviewWrapper {
                // Theme roles, read from the composition under test rather than hardcoded.
                primary = MaterialTheme.colorScheme.primary
                tertiary = MaterialTheme.colorScheme.tertiary
                captured = block()
            }
        }
        composeTestRule.waitForIdle()
        return captured
    }

    @Test
    fun `without the qualifier the title is the bare app name in the brand color`() {
        val result = capture { brandTitle(includeQualifier = false, highlightQualifier = false) }

        result.text shouldBe name
        result.spanStyles.size shouldBe 1
        result.spanStyles.single().item.color shouldBe primary
        result.text.substring(
            result.spanStyles.single().start,
            result.spanStyles.single().end,
        ) shouldBe name
    }

    // The regression guard for the two-flag split: this is the FOSS status-free view, which needs
    // the qualifier present but NOT highlighted. Collapsing the flags drops the word entirely;
    // highlighting on `includeQualifier` alone hands out the earned styling for free. Both would
    // still produce plausible-looking text, so the span colors are the assertion that matters.
    @Test
    fun `an included but unhighlighted qualifier is present and wears the plain color`() {
        val result = capture { brandTitle(includeQualifier = true, highlightQualifier = false) }

        result.text shouldBe composed
        result.text.contains(qualifier) shouldBe true
        result.spanStyles.size shouldBe 2
        result.spanStyles[0].item.color shouldBe primary
        result.spanStyles[1].item.color shouldBe primary
    }

    @Test
    fun `a highlighted qualifier carries the accent span over the qualifier only`() {
        val result = capture { brandTitle(includeQualifier = true, highlightQualifier = true) }

        result.text shouldBe composed
        result.spanStyles.size shouldBe 2

        val base = result.spanStyles[0]
        base.item.color shouldBe primary
        result.text.substring(base.start, base.end) shouldBe name

        val highlight = result.spanStyles[1]
        highlight.item.color shouldBe tertiary
        highlight.item.color shouldNotBe base.item.color
        // Not just "a span exists" — the bug class this replaces renders perfectly correct text
        // with the highlight sitting on the app name instead of the qualifier.
        result.text.substring(highlight.start, highlight.end) shouldBe qualifier
    }

    // The markers are injected as format arguments, so a template or formatter that mangled them
    // would leak U+FFFC / U+FFF9 into the toolbar.
    @Test
    fun `neither splice marker survives into the rendered title`() {
        val result = capture { brandTitle(includeQualifier = true, highlightQualifier = true) }

        result.text shouldNotContain BRAND_TITLE_MARKER
        result.text shouldNotContain BRAND_QUALIFIER_MARKER
    }

    @Test
    fun `the string form matches the annotated form`() {
        val result = capture { AnnotatedString(brandTitleText(includeQualifier = true)) }

        result.text shouldBe composed
    }
}
