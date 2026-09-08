package eu.darken.butler.workspace.ui.layout

import androidx.compose.ui.unit.dp
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

    @Test
    fun `a small window offers only the single rail geometry`() {
        offeredGeometries(360.dp, 780.dp, WorkspacePanelMode.AUTO) shouldBe listOf(WorkspacePanelMode.SINGLE_RAIL)
    }

    @Test
    fun `a phone offers the dual that splits its long side`() {
        offeredGeometries(915.dp, 412.dp, WorkspacePanelMode.AUTO) shouldBe listOf(
            WorkspacePanelMode.SINGLE_RAIL,
            WorkspacePanelMode.DUAL_VERTICAL,
        )
        offeredGeometries(412.dp, 915.dp, WorkspacePanelMode.AUTO) shouldBe listOf(
            WorkspacePanelMode.SINGLE_RAIL,
            WorkspacePanelMode.DUAL_HORIZONTAL,
        )
    }

    @Test
    fun `a tablet offers both duals and the triples`() {
        offeredGeometries(1280.dp, 800.dp, WorkspacePanelMode.AUTO) shouldBe listOf(
            WorkspacePanelMode.SINGLE_RAIL,
            WorkspacePanelMode.DUAL_VERTICAL,
            WorkspacePanelMode.DUAL_HORIZONTAL,
            WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT,
            WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT,
        )
        offeredGeometries(1200.dp, 900.dp, WorkspacePanelMode.AUTO) shouldBe ADAPTIVE_GEOMETRIES
    }

    @Test
    fun `the stored geometry is always offered`() {
        offeredGeometries(412.dp, 915.dp, WorkspacePanelMode.QUAD_GRID) shouldBe listOf(
            WorkspacePanelMode.SINGLE_RAIL,
            WorkspacePanelMode.DUAL_HORIZONTAL,
            WorkspacePanelMode.QUAD_GRID,
        )
    }

    /** The icon getters build a fresh instance per call, so compare by name. */
    @Test
    fun `the rail icon follows the orientation`() {
        WorkspacePanelMode.SINGLE_RAIL.icon(landscape = false).name shouldBe "LayoutSingleRail"
        WorkspacePanelMode.SINGLE_RAIL.icon(landscape = true).name shouldBe "LayoutSingleRailStart"
        WorkspacePanelMode.DUAL_VERTICAL.icon(landscape = true).name shouldBe
            WorkspacePanelMode.DUAL_VERTICAL.icon().name
    }
}
