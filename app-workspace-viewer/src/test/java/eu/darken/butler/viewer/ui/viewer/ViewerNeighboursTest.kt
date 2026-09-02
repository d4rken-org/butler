package eu.darken.butler.viewer.ui.viewer

import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Where a file step lands, as pure list arithmetic: null means the listing no longer holds the file
 * on display, which the ViewModel turns into "no arrows" rather than a step into the wrong folder.
 */
class ViewerNeighboursTest : BaseTest() {

    private val a = LocalPath.build("/storage/emulated/0/DCIM/a.jpg")
    private val b = LocalPath.build("/storage/emulated/0/DCIM/b.jpg")
    private val c = LocalPath.build("/storage/emulated/0/DCIM/c.jpg")
    private val files = listOf(a, b, c)

    @Test
    fun `a file in the middle can step both ways`() {
        resolveNeighbours(b, files) shouldBe ViewerNeighbours(current = b, previous = a, next = c)
    }

    @Test
    fun `the first file has nothing before it`() {
        resolveNeighbours(a, files) shouldBe ViewerNeighbours(current = a, previous = null, next = b)
    }

    @Test
    fun `the last file has nothing after it`() {
        resolveNeighbours(c, files) shouldBe ViewerNeighbours(current = c, previous = b, next = null)
    }

    @Test
    fun `a file alone in its listing still resolves`() {
        // Not the same as "no listing": the arrows are offered, both of them disabled.
        resolveNeighbours(a, listOf(a)) shouldBe ViewerNeighbours(current = a, previous = null, next = null)
    }

    @Test
    fun `a file the listing does not hold has no neighbours at all`() {
        resolveNeighbours(LocalPath.build("/storage/emulated/0/DCIM/gone.jpg"), files) shouldBe null
        resolveNeighbours(a, emptyList()) shouldBe null
    }

    @Test
    fun `the result names the file it was resolved for`() {
        resolveNeighbours(b, files)!!.current shouldBe b
    }
}
