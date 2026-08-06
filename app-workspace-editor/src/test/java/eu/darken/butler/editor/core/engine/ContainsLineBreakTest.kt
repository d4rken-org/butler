package eu.darken.butler.editor.core.engine

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import testhelpers.BaseTest

/** The break predicate guarding the single-line fast paths and the typing-run coalescer. */
class ContainsLineBreakTest : BaseTest() {

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `every kind of break counts`(terminator: String) {
        "ab${terminator}cd".containsLineBreak().shouldBeTrue()
        terminator.containsLineBreak().shouldBeTrue()
    }

    @Test
    fun `break-free text does not`() {
        "abc".containsLineBreak().shouldBeFalse()
        "".containsLineBreak().shouldBeFalse()
        // Other whitespace and unicode line separators are not document breaks here
        "a\tb c".containsLineBreak().shouldBeFalse()
    }
}
