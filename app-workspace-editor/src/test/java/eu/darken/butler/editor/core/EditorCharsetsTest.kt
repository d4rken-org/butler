package eu.darken.butler.editor.core

import eu.darken.butler.common.files.text.TextCharsets
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class EditorCharsetsTest : BaseTest() {

    @Test
    fun `resolves canonical names`() {
        EditorCharsets.resolve("UTF-8") shouldBe Charsets.UTF_8
        EditorCharsets.resolve("UTF-16LE") shouldBe Charsets.UTF_16LE
        EditorCharsets.resolve("UTF-16BE") shouldBe Charsets.UTF_16BE
        EditorCharsets.resolve("ISO-8859-1") shouldBe Charsets.ISO_8859_1
        EditorCharsets.resolve("windows-1252")?.name() shouldBe "windows-1252"
        EditorCharsets.resolve("US-ASCII") shouldBe Charsets.US_ASCII
        EditorCharsets.resolve("GB18030") shouldBe TextCharsets.GB18030
    }

    @Test
    fun `allowlist is the selectable charsets in their display order`() {
        EditorCharsets.allowlist shouldBe TextCharsets.selectable
    }

    @Test
    fun `resolves the names the viewer hands over`() {
        for (charset in TextCharsets.selectable) {
            EditorCharsets.resolve(charset.name()) shouldBe charset
        }
    }

    @Test
    fun `resolves aliases within the allowlist`() {
        EditorCharsets.resolve("utf8") shouldBe Charsets.UTF_8
        EditorCharsets.resolve("latin1") shouldBe Charsets.ISO_8859_1
        EditorCharsets.resolve("cp1252")?.name() shouldBe "windows-1252"
    }

    @Test
    fun `rejects unknown and non-allowlisted charsets`() {
        EditorCharsets.resolve(null).shouldBeNull()
        EditorCharsets.resolve("").shouldBeNull()
        EditorCharsets.resolve("not-a-charset").shouldBeNull()
        // Valid JVM charset, but not allowlisted (no block snapping or byte mapping for it)
        EditorCharsets.resolve("Shift_JIS").shouldBeNull()
    }
}
