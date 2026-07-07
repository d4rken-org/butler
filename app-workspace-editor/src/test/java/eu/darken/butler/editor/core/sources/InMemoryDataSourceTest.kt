package eu.darken.butler.editor.core.sources

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class InMemoryDataSourceTest : BaseTest() {

    @Test
    fun `getSize matches getMeta for multibyte content`() = runTest {
        // "好" is 1 UTF-16 unit but 3 UTF-8 bytes - both accessors must agree on bytes
        val source = InMemoryDataSource(Workspace.Id(), "a好b")

        source.getSize() shouldBe 5L
        source.getSize() shouldBe source.getMeta().size
    }
}
