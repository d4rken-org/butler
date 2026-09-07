package eu.darken.butler.workspace.ui.layout

import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test
import testhelpers.BaseTest

/**
 * The three surfaces Settings offers, mapped onto the stored panel mode. Everything that is not
 * AUTO or SINGLE is an adaptive surface, so a geometry pinned from the rail still reads as Adaptive.
 */
class WorkspaceLayoutSurfaceTest : BaseTest() {

    @Test
    fun `every panel mode maps to a surface`() {
        WorkspacePanelMode.AUTO.surface shouldBe WorkspaceLayoutSurface.AUTOMATIC
        WorkspacePanelMode.SINGLE.surface shouldBe WorkspaceLayoutSurface.CLASSIC
        WorkspacePanelMode.entries
            .filter { it != WorkspacePanelMode.AUTO && it != WorkspacePanelMode.SINGLE }
            .forEach { it.surface shouldBe WorkspaceLayoutSurface.ADAPTIVE }
    }

    @Test
    fun `each surface round-trips through its panel mode`() {
        WorkspaceLayoutSurface.entries.forEach { surface ->
            surface.toPanelMode().surface shouldBe surface
        }
    }

    @Test
    fun `the pinnable geometries are every mode but the three surfaces`() {
        ADAPTIVE_GEOMETRIES shouldBe WorkspacePanelMode.entries.filter {
            it != WorkspacePanelMode.AUTO &&
                it != WorkspacePanelMode.SINGLE &&
                it != WorkspacePanelMode.ADAPTIVE
        }
        WorkspacePanelMode.ADAPTIVE.isPinnedGeometry shouldBe false
        WorkspacePanelMode.DUAL_VERTICAL.isPinnedGeometry shouldBe true
    }

    /** The setting is stored as JSON, so the added value needs a stable serial name. */
    @Test
    fun `the adaptive mode survives serialization`() {
        val encoded = Json.encodeToString(WorkspacePanelMode.serializer(), WorkspacePanelMode.ADAPTIVE)
        encoded shouldBe "\"ADAPTIVE\""
        Json.decodeFromString(WorkspacePanelMode.serializer(), encoded) shouldBe WorkspacePanelMode.ADAPTIVE
    }
}
