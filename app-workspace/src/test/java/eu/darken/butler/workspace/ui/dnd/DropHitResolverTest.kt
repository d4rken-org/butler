package eu.darken.butler.workspace.ui.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DropHitResolverTest : BaseTest() {

    private val download = LocalPath.build("/storage/emulated/0/Download")
    private val pictures = LocalPath.build("/storage/emulated/0/Pictures")
    private val readOnly = LocalPath.build("/system")

    // The page: a 400x800 content area whose top 100px are covered by the floating bar stack.
    private val contentBand = Rect(0f, 100f, 400f, 800f)

    private fun resolve(
        position: Offset,
        registry: DropZoneRegistry,
        isValidExplicit: (APath<*>) -> Boolean = { true },
    ) = resolveDropHit(
        positionInRoot = position,
        zones = registry::zoneAt,
        contentBand = contentBand,
        isValidExplicit = isValidExplicit,
    )

    @Test
    fun `a valid zone resolves to its destination`() {
        val registry = DropZoneRegistry().apply {
            register("row", download, Rect(0f, 200f, 400f, 260f))
        }

        resolve(Offset(200f, 230f), registry) shouldBe DropHit.Explicit(download)
    }

    @Test
    fun `an invalid zone blocks instead of falling through to the pane`() {
        val registry = DropZoneRegistry().apply {
            register("row", readOnly, Rect(0f, 200f, 400f, 260f))
        }

        resolve(Offset(200f, 230f), registry, isValidExplicit = { false }) shouldBe DropHit.Blocked
    }

    @Test
    fun `content background inside the band is a pane drop`() {
        resolve(Offset(200f, 400f), DropZoneRegistry()) shouldBe DropHit.Pane
    }

    @Test
    fun `the bar band without a zone is not a drop at all`() {
        resolve(Offset(200f, 40f), DropZoneRegistry()) shouldBe DropHit.None
    }

    @Test
    fun `a crumb zone inside the bar band still resolves`() {
        val registry = DropZoneRegistry().apply {
            register("crumb", download, Rect(20f, 20f, 120f, 60f), allowOutsideContentBand = true)
        }

        resolve(Offset(50f, 40f), registry) shouldBe DropHit.Explicit(download)
    }

    @Test
    fun `a row zone under the bar band resolves to nothing`() {
        val registry = DropZoneRegistry().apply {
            register("row", download, Rect(0f, 20f, 400f, 80f))
        }

        resolve(Offset(200f, 40f), registry) shouldBe DropHit.None
    }

    @Test
    fun `an eligible crumb wins over a hidden row it overlaps`() {
        val registry = DropZoneRegistry().apply {
            register("row", download, Rect(30f, 20f, 90f, 80f))
            register("crumb", pictures, Rect(0f, 20f, 300f, 60f), allowOutsideContentBand = true)
        }

        resolve(Offset(50f, 40f), registry) shouldBe DropHit.Explicit(pictures)
    }
}
