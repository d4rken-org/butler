package eu.darken.butler.common

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Locale

class DocsUrlsTest {

    private val origin = "https://butler.darken.eu"
    private val german = listOf(Locale.forLanguageTag("de-AT"))

    @Test
    fun `localizes a guide and preserves query and section`() {
        DocsUrls.localize("https://butler.darken.eu/setup?source=app#root-access", german) shouldBe
            "https://butler.darken.eu/de/setup?source=app#root-access"
    }

    @Test
    fun `localizes home with or without a slash`() {
        listOf("", "/").forEach { suffix ->
            DocsUrls.localize("https://butler.darken.eu$suffix#download", german) shouldBe
                "https://butler.darken.eu/de/#download"
        }
    }

    @Test
    fun `keeps legal pages and existing translations unchanged`() {
        listOf("privacy", "security", "changelog", "de/setup").forEach { path ->
            val url = "$origin/$path#section"
            DocsUrls.localize(url, german) shouldBe url
        }
    }

    @Test
    fun `chooses first supported locale and otherwise leaves English`() {
        val url = "https://butler.darken.eu/setup"
        DocsUrls.localize(url, listOf(Locale.FRENCH, Locale.GERMAN)) shouldBe "https://butler.darken.eu/de/setup"
        DocsUrls.localize(url, listOf(Locale.FRENCH)) shouldBe url
        DocsUrls.localize(url, emptyList()) shouldBe url
        DocsUrls.localize(url, listOf(Locale.ENGLISH, Locale.GERMAN)) shouldBe url
        DocsUrls.localize(url, listOf(Locale.GERMAN, Locale.ENGLISH)) shouldBe "$origin/de/setup"
    }

    @Test
    fun `does not rewrite unrelated origins`() {
        listOf(
            "https://github.com/d4rken-org/butler",
            "https://butler.darken.eu.example/setup",
            "https://butler.darken.eu@example.com/setup",
            "http://butler.darken.eu/setup",
        ).forEach { url -> DocsUrls.localize(url, german) shouldBe url }
    }
}
