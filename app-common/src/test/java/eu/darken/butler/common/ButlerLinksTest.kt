package eu.darken.butler.common

import eu.darken.butler.setup.core.SetupModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.net.URI

class ButlerLinksTest : BaseTest() {

    private val repositoryLinks = listOf(
        ButlerLinks.CHANGELOG,
        ButlerLinks.ISSUES,
    )

    private val documentationLinks = listOf(
        ButlerLinks.DOCUMENTATION,
        ButlerLinks.PRIVACY_POLICY,
    ) + SetupModule.Type.entries.map { it.helpUrl }

    @Test
    fun `links point at the d4rken-org butler repository`() {
        repositoryLinks.forEach { it shouldContain "github.com/d4rken-org/butler" }
    }

    @Test
    fun `links do not use the wrong d4rken butler slug`() {
        (repositoryLinks + documentationLinks).forEach { it shouldNotContain "github.com/d4rken/butler" }
    }

    @Test
    fun `documentation links use the Butler site`() {
        documentationLinks.forEach {
            val uri = URI(it)
            uri.scheme shouldBe "https"
            uri.host shouldBe "butler.darken.eu"
        }
    }

    @Test
    fun `every setup module links to a distinct setup section`() {
        val sections = SetupModule.Type.entries.map {
            val uri = URI(it.helpUrl)
            uri.path shouldBe "/setup"
            uri.fragment.isNullOrBlank() shouldBe false
            uri.fragment
        }
        sections.distinct().size shouldBe SetupModule.Type.entries.size
    }
}
