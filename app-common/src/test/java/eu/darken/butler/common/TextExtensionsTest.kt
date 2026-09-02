package eu.darken.butler.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TextExtensionsTest : BaseTest() {

    @Test
    fun `traditional whitespace characters are detected`() {
        ' '.isProblematicInvisible() shouldBe true
        '\t'.isProblematicInvisible() shouldBe true
        '\n'.isProblematicInvisible() shouldBe true
        '\r'.isProblematicInvisible() shouldBe true
        '\u00A0'.isProblematicInvisible() shouldBe true  // Non-breaking space
        '\u2003'.isProblematicInvisible() shouldBe true  // Em space
    }

    @Test
    fun `zero-width characters are detected`() {
        '\u200B'.isProblematicInvisible() shouldBe true  // Zero-width space
        '\u200C'.isProblematicInvisible() shouldBe true  // Zero-width non-joiner
        '\u200D'.isProblematicInvisible() shouldBe true  // Zero-width joiner
        '\u2060'.isProblematicInvisible() shouldBe true  // Word joiner
        '\uFEFF'.isProblematicInvisible() shouldBe true  // Zero-width no-break space (BOM)
    }

    @Test
    fun `bidirectional control characters are detected`() {
        // Left-to-right and right-to-left marks
        '\u200E'.isProblematicInvisible() shouldBe true  // Left-to-Right Mark (LRM)
        '\u200F'.isProblematicInvisible() shouldBe true  // Right-to-Left Mark (RLM)

        // Embedding and override controls
        '\u202A'.isProblematicInvisible() shouldBe true  // Left-to-Right Embedding (LRE)
        '\u202B'.isProblematicInvisible() shouldBe true  // Right-to-Left Embedding (RLE)
        '\u202C'.isProblematicInvisible() shouldBe true  // Pop Directional Formatting (PDF)
        '\u202D'.isProblematicInvisible() shouldBe true  // Left-to-Right Override (LRO)
        '\u202E'.isProblematicInvisible() shouldBe true  // Right-to-Left Override (RLO) - SECURITY RISK!

        // Isolate controls
        '\u2066'.isProblematicInvisible() shouldBe true  // Left-to-Right Isolate (LRI)
        '\u2067'.isProblematicInvisible() shouldBe true  // Right-to-Left Isolate (RLI)
        '\u2068'.isProblematicInvisible() shouldBe true  // First Strong Isolate (FSI)
        '\u2069'.isProblematicInvisible() shouldBe true  // Pop Directional Isolate (PDI)
    }

    @Test
    fun `format characters are detected`() {
        '\u00AD'.isProblematicInvisible() shouldBe true  // Soft hyphen
    }

    @Test
    fun `C0 control characters are detected`() {
        '\u0000'.isProblematicInvisible() shouldBe true  // NULL
        '\u0001'.isProblematicInvisible() shouldBe true  // Start of Heading
        '\u0007'.isProblematicInvisible() shouldBe true  // Bell
        '\u0008'.isProblematicInvisible() shouldBe true  // Backspace
        '\u001B'.isProblematicInvisible() shouldBe true  // Escape
        '\u001F'.isProblematicInvisible() shouldBe true  // Unit Separator (last C0 control)
    }

    @Test
    fun `DEL and C1 control characters are detected`() {
        '\u007F'.isProblematicInvisible() shouldBe true  // Delete
        '\u0080'.isProblematicInvisible() shouldBe true  // First C1 control
        '\u0090'.isProblematicInvisible() shouldBe true  // Device Control String
        '\u009F'.isProblematicInvisible() shouldBe true  // Application Program Command (last C1 control)
    }

    @Test
    fun `normal visible ASCII characters are not detected`() {
        'a'.isProblematicInvisible() shouldBe false
        'Z'.isProblematicInvisible() shouldBe false
        '0'.isProblematicInvisible() shouldBe false
        '9'.isProblematicInvisible() shouldBe false
        '_'.isProblematicInvisible() shouldBe false
        '.'.isProblematicInvisible() shouldBe false
        '-'.isProblematicInvisible() shouldBe false
        '!'.isProblematicInvisible() shouldBe false
        '@'.isProblematicInvisible() shouldBe false
        '#'.isProblematicInvisible() shouldBe false
        '('.isProblematicInvisible() shouldBe false
        ')'.isProblematicInvisible() shouldBe false
    }

    @Test
    fun `normal visible Unicode characters are not detected`() {
        // Latin extended
        'ä'.isProblematicInvisible() shouldBe false
        'ñ'.isProblematicInvisible() shouldBe false
        'ø'.isProblematicInvisible() shouldBe false

        // Cyrillic
        'Я'.isProblematicInvisible() shouldBe false

        // Greek
        'α'.isProblematicInvisible() shouldBe false

        // CJK
        '中'.isProblematicInvisible() shouldBe false
        '日'.isProblematicInvisible() shouldBe false
        '한'.isProblematicInvisible() shouldBe false

        // Emoji (represented as strings since they use multiple code units)
        "🎉".all { !it.isProblematicInvisible() } shouldBe true
        '✓'.isProblematicInvisible() shouldBe false
    }

    @Test
    fun `edge cases around control character boundaries`() {
        // Just before C0 controls end
        '\u001E'.isProblematicInvisible() shouldBe true   // Record Separator

        // Space (normal printable but whitespace)
        '\u0020'.isProblematicInvisible() shouldBe true   // Space

        // Just after space (first normal printable non-whitespace)
        '\u0021'.isProblematicInvisible() shouldBe false  // '!'

        // Just before DEL
        '\u007E'.isProblematicInvisible() shouldBe false  // '~'

        // Just after C1 controls end
        '\u00A0'.isProblematicInvisible() shouldBe true   // Non-breaking space (whitespace)
        '\u00A1'.isProblematicInvisible() shouldBe false  // '¡'
    }

    @Test
    fun `filename spoofing attack example is detected`() {
        // The dangerous RLO character used in malware filename spoofing
        val rlo = '\u202E'
        rlo.isProblematicInvisible() shouldBe true

        // Simulating a spoofed filename: "photo[RLO]gpj.exe"
        // This would display as "photoexe.jpg" - hiding the .exe extension!
        val filename = "photo${rlo}gpj.exe"
        filename.any { it.isProblematicInvisible() } shouldBe true
    }

    @Test
    fun `soft breaks are added after separators`() {
        val zwsp = '\u200B'
        "aaaaa-bbbbb_ccccc.ddddd+eeeee".withSoftBreaks() shouldBe
            "aaaaa-${zwsp}bbbbb_${zwsp}ccccc.${zwsp}ddddd+${zwsp}eeeee"
    }

    @Test
    fun `soft breaks leave text without separators alone`() {
        "plainname".withSoftBreaks() shouldBe "plainname"
        "with spaces only".withSoftBreaks() shouldBe "with spaces only"
        "".withSoftBreaks() shouldBe ""
    }

    @Test
    fun `soft breaks keep a short tail attached`() {
        // Line filling is greedy, so a break offered before the extension would take it and strand
        // the extension on a line of its own
        "termux-app_v0.118.3+github-debug_universal.apk".withSoftBreaks() shouldBe
            "termux-\u200Bapp_\u200Bv0.\u200B118.\u200B3+\u200Bgithub-\u200Bdebug_\u200Buniversal.apk"
    }

    @Test
    fun `soft breaks keep a short head attached`() {
        // A break after a leading dot would put the dot alone on the first line, which is the
        // orphaned-bullet defect all over again
        ".AVeryLongNameWithoutOtherSeparators".withSoftBreaks() shouldBe
            ".AVeryLongNameWithoutOtherSeparators"
        // Separators further in are still offered
        ".hidden_config_name_here".withSoftBreaks() shouldBe ".hidden_\u200Bconfig_\u200Bname_here"
    }

    @Test
    fun `soft breaks measure the tail in code points`() {
        // Three code points, but five Chars: counting Chars would offer a break here
        "name-😀😀a".withSoftBreaks() shouldBe "name-😀😀a"
    }

    @Test
    fun `soft breaks never trail the text`() {
        // A trailing zero-width space would let the line wrap after the last character, producing an
        // empty final line
        "archive.tar.".withSoftBreaks() shouldBe "archive.tar."
        "-".withSoftBreaks() shouldBe "-"
    }

    @Test
    fun `soft breaks preserve the visible text`() {
        val name = "termux-app_v0.118.3+github-debug_universal.apk"
        name.withSoftBreaks().filterNot { it == '\u200B' } shouldBe name
    }
}
