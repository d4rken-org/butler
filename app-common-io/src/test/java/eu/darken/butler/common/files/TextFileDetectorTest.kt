package eu.darken.butler.common.files

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TextFileDetectorTest : BaseTest() {

    @Test
    fun `all three overloads agree on the same file`() {
        TextFileDetector.isTextFile("a.yaml") shouldBe true
        TextFileDetector.isTextFile(MimeInfo.fromFileName("a.yaml")) shouldBe true
        TextFileDetector.isTextFile(LocalPath.build("/x/a.yaml")) shouldBe true

        TextFileDetector.isTextFile("a.png") shouldBe false
        TextFileDetector.isTextFile(MimeInfo.fromFileName("a.png")) shouldBe false
        TextFileDetector.isTextFile(LocalPath.build("/x/a.png")) shouldBe false
    }
}
