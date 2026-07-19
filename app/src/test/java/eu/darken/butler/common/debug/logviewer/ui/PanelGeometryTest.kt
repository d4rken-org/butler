package eu.darken.butler.common.debug.logviewer.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PanelGeometryTest : BaseTest() {

    @Test
    fun `size within bounds passes through`() {
        PanelGeometry.clampSize(desired = 300f, minSize = 200f, container = 1000f) shouldBe 300f
    }

    @Test
    fun `size below minimum is raised`() {
        PanelGeometry.clampSize(desired = 100f, minSize = 200f, container = 1000f) shouldBe 200f
    }

    @Test
    fun `size above container is capped`() {
        PanelGeometry.clampSize(desired = 1200f, minSize = 200f, container = 1000f) shouldBe 1000f
    }

    @Test
    fun `container smaller than minimum does not throw and fills the container`() {
        // The multi-window case: safe area narrower than the panel's nominal minimum.
        PanelGeometry.clampSize(desired = 300f, minSize = 200f, container = 150f) shouldBe 150f
        PanelGeometry.clampSize(desired = 100f, minSize = 200f, container = 150f) shouldBe 150f
    }

    @Test
    fun `degenerate zero or negative containers stay safe`() {
        PanelGeometry.clampSize(desired = 300f, minSize = 200f, container = 0f) shouldBe 0f
        PanelGeometry.clampSize(desired = 300f, minSize = 200f, container = -50f) shouldBe -50f
    }

    @Test
    fun `offset is clamped into the container`() {
        PanelGeometry.clampOffset(offset = 50f, activeSize = 100f, container = 1000f) shouldBe 50f
        PanelGeometry.clampOffset(offset = -10f, activeSize = 100f, container = 1000f) shouldBe 0f
        PanelGeometry.clampOffset(offset = 950f, activeSize = 100f, container = 1000f) shouldBe 900f
    }

    @Test
    fun `element larger than container pins to zero`() {
        PanelGeometry.clampOffset(offset = 40f, activeSize = 200f, container = 150f) shouldBe 0f
    }
}
