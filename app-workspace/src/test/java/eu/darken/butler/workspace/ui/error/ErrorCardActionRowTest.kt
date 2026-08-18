package eu.darken.butler.workspace.ui.error

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The action row used to be a `Row` of `weight(1f)` buttons, which split the card into equal shares
 * regardless of label length and wrapped the longer labels onto a second line.
 *
 * These assert the branch contract — actions share a row given room, stack full-width when denied it,
 * both in reading order — and nothing about dp budgets. Robolectric substitutes the font and measures
 * text at a fixed height and 1px per character (`.claude/rules/testing.md`), so no width assertion
 * made here would mean anything about a real device, and no label can be made to wrap on demand.
 * Whether the three English labels actually fit across a Pixel 8 was settled instead by measuring
 * Roboto Medium's advance widths directly: 291.1.dp of the 332.dp a card gets there.
 *
 * The stacking test is the one that pins the regression — the old weighted row never stacked at any
 * width, it just squeezed and wrapped.
 */
class ErrorCardActionRowTest : ComposeTest() {

    private class FixableError(private val fixLabel: String?) : RuntimeException("boom"), HasLocalizedError {
        override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
            throwable = this,
            label = LABEL.toCaString(),
            description = BODY.toCaString(),
            fixActionLabel = fixLabel?.toCaString(),
            fixAction = fixLabel?.let { { } },
        )
    }

    /**
     * Bounds of the *button*, not of its label: the default merged semantics tree reports the
     * clickable ancestor that absorbed the text.
     */
    private fun boundsOf(text: String) = composeTestRule.onNodeWithText(text).getBoundsInRoot()

    private fun setCard(width: Int, fixLabel: String? = FIX, onRetry: (() -> Unit)? = {}) {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.width(width.dp)) {
                    ErrorCard(
                        title = TITLE,
                        error = FixableError(fixLabel),
                        onShareError = {},
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    @Test
    fun `actions share a row in reading order when there is room`() {
        setCard(width = ROOMY)

        val retry = boundsOf(RETRY)
        val fix = boundsOf(FIX)
        val share = boundsOf(SHARE)

        retry.top shouldBe fix.top
        fix.top shouldBe share.top
        (fix.left >= retry.right) shouldBe true
        (share.left >= fix.right) shouldBe true
    }

    @Test
    fun `an error without a fix action still shares a row`() {
        setCard(width = ROOMY, fixLabel = null)

        val retry = boundsOf(RETRY)
        val share = boundsOf(SHARE)

        retry.top shouldBe share.top
        (share.left >= retry.right) shouldBe true
    }

    @Test
    fun `actions stack full-width in reading order when there is not`() {
        setCard(width = CRAMPED)

        val retry = boundsOf(RETRY)
        val fix = boundsOf(FIX)
        val share = boundsOf(SHARE)

        // Stacked top to bottom, which is also the focus order.
        (fix.top >= retry.bottom) shouldBe true
        (share.top >= fix.bottom) shouldBe true

        // Full width, so all three agree on both edges.
        fix.left shouldBe retry.left
        share.left shouldBe retry.left
        fix.right shouldBe retry.right
        share.right shouldBe retry.right
    }

    @Test
    fun `a share-only card still lays out its single action`() {
        setCard(width = PIXEL8_CARD, fixLabel = null, onRetry = null)

        val share = boundsOf(SHARE)

        (share.width.value > 0f) shouldBe true
        (share.height.value > 0f) shouldBe true
    }

    companion object {
        private const val TITLE = "Can't show this file"
        private const val LABEL = "Permission required"
        private const val BODY = "Cannot access the file due to insufficient permissions."
        private const val RETRY = "Retry"
        private const val SHARE = "Share Error"
        private const val FIX = "Open Setup"

        /** Wide enough that any font puts three buttons on one row. */
        private const val ROOMY = 1200

        /** Narrow enough that no font does. */
        private const val CRAMPED = 200

        /** A Pixel 8's 412.dp, less the viewer's 24.dp card padding on each side. */
        private const val PIXEL8_CARD = 412 - 48
    }
}
