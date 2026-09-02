package eu.darken.butler.workspace.ui.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DropZoneRegistryTest : BaseTest() {

    private val download = LocalPath.build("/storage/emulated/0/Download")
    private val pictures = LocalPath.build("/storage/emulated/0/Pictures")

    @Test
    fun `a registered zone is found by a point inside it`() {
        val registry = DropZoneRegistry()
        registry.register("row", download, Rect(0f, 0f, 100f, 50f))

        registry.zoneAt(Offset(50f, 25f))?.destination shouldBe download
    }

    @Test
    fun `a point outside every zone finds nothing`() {
        val registry = DropZoneRegistry()
        registry.register("row", download, Rect(0f, 0f, 100f, 50f))

        registry.zoneAt(Offset(150f, 25f)) shouldBe null
    }

    @Test
    fun `registering the same key again replaces the zone`() {
        val registry = DropZoneRegistry()
        registry.register("row", download, Rect(0f, 0f, 100f, 50f))
        registry.register("row", pictures, Rect(0f, 100f, 100f, 150f))

        registry.zoneAt(Offset(50f, 25f)) shouldBe null
        registry.zoneAt(Offset(50f, 125f))?.destination shouldBe pictures
    }

    @Test
    fun `unregistering removes the zone`() {
        val registry = DropZoneRegistry()
        registry.register("row", download, Rect(0f, 0f, 100f, 50f))
        registry.unregister("row")

        registry.zoneAt(Offset(50f, 25f)) shouldBe null
    }

    @Test
    fun `nested zones resolve to the smallest one`() {
        val registry = DropZoneRegistry()
        registry.register("bar", download, Rect(0f, 0f, 400f, 100f))
        registry.register("crumb", pictures, Rect(10f, 10f, 90f, 60f))

        registry.zoneAt(Offset(50f, 25f))?.key shouldBe "crumb"
        registry.zoneAt(Offset(200f, 25f))?.key shouldBe "bar"
    }

    @Test
    fun `hover is set and cleared`() {
        val registry = DropZoneRegistry()
        registry.hoveredKey shouldBe null

        registry.setHovered("row")
        registry.hoveredKey shouldBe "row"

        registry.setHovered(null)
        registry.hoveredKey shouldBe null
    }

    @Test
    fun `unregistering the hovered zone clears the hover`() {
        val registry = DropZoneRegistry()
        registry.register("row", download, Rect(0f, 0f, 100f, 50f))
        registry.setHovered("row")

        registry.unregister("row")

        registry.hoveredKey shouldBe null
    }
}
