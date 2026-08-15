package eu.darken.butler.workspace.ui.modal

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Test

class PaneFocusPulseTest {

    @Test
    fun `the pulse grows from its start radius to its end radius`() {
        pulseRadius(0f) shouldBe 24.dp
        pulseRadius(1f) shouldBe 96.dp
    }

    @Test
    fun `the pulse fades out as it grows`() {
        pulseAlpha(0f) shouldBe 0.3f
        pulseAlpha(1f) shouldBe 0f
    }
}
