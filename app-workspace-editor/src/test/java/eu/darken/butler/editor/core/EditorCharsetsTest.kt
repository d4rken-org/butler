package eu.darken.butler.editor.core

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
        // Valid JVM charset, but not allowlisted (DBCS needs DBCS-aware block snapping)
        EditorCharsets.resolve("Shift_JIS").shouldBeNull()
    }
}
