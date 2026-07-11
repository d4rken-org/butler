package eu.darken.butler.editor.core.syntax

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LanguageTest {

    @Test
    fun `maps known extensions`() {
        Language.fromExtension("js") shouldBe Language.JAVASCRIPT
        Language.fromExtension("mjs") shouldBe Language.JAVASCRIPT
        Language.fromExtension("cjs") shouldBe Language.JAVASCRIPT
        Language.fromExtension("sh") shouldBe Language.BASH
        Language.fromExtension("bash") shouldBe Language.BASH
        Language.fromExtension("zsh") shouldBe Language.BASH
        Language.fromExtension("ksh") shouldBe Language.BASH
        Language.fromExtension("md") shouldBe Language.MARKDOWN
        Language.fromExtension("markdown") shouldBe Language.MARKDOWN
        Language.fromExtension("json") shouldBe Language.JSON
    }

    @Test
    fun `extension matching is case-insensitive`() {
        Language.fromExtension("JS") shouldBe Language.JAVASCRIPT
        Language.fromExtension("Md") shouldBe Language.MARKDOWN
        Language.fromFileName("SCRIPT.SH") shouldBe Language.BASH
    }

    @Test
    fun `unknown or absent extensions map to null`() {
        Language.fromExtension("kt") shouldBe null
        Language.fromExtension("jsx") shouldBe null // dropped from v1: JS tokenizer isn't JSX-aware
        Language.fromExtension("") shouldBe null
        Language.fromExtension(null) shouldBe null
    }

    @Test
    fun `file name based detection`() {
        Language.fromFileName("app.js") shouldBe Language.JAVASCRIPT
        Language.fromFileName("notes.v2.md") shouldBe Language.MARKDOWN
        Language.fromFileName("noextension") shouldBe null
        Language.fromFileName("trailingdot.") shouldBe null
        Language.fromFileName(".json") shouldBe null // dotfile, not an extension
        Language.fromFileName(null) shouldBe null
    }
}
