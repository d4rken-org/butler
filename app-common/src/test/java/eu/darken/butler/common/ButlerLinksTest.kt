package eu.darken.butler.common

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ButlerLinksTest : BaseTest() {

    private val links = listOf(
        ButlerLinks.CHANGELOG,
        ButlerLinks.WIKI,
        ButlerLinks.ISSUES,
    )

    @Test
    fun `links point at the d4rken-org butler repository`() {
        links.forEach { it shouldContain "github.com/d4rken-org/butler" }
    }

    @Test
    fun `links do not use the wrong d4rken butler slug`() {
        links.forEach { it shouldNotContain "github.com/d4rken/butler" }
    }
}
