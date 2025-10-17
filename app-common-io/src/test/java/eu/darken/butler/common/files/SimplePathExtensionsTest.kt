package eu.darken.butler.common.files

import eu.darken.butler.common.files.extensions.crumbsTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SimplePathExtensionsTest : BaseTest() {

    @Test
    fun `test chunking`() {
        val parent = LocalPath.build("/the/parent/")
        val child = LocalPath.build("/the/parent/has/a/child/")

        val crumbs = parent.crumbsTo(child)

        crumbs shouldBe arrayOf("has", "a", "child")
    }

}