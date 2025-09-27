package eu.darken.butler.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.text.DecimalFormatSymbols
import java.util.Locale


class ByteFormatterTest : BaseTest() {

    @Test fun `unit stripping`() {
        val ds = DecimalFormatSymbols(Locale.getDefault()).decimalSeparator
        stripSizeUnit("14 GB") shouldBe 14.0
        stripSizeUnit("14GB") shouldBe 14.0
        stripSizeUnit("14${ds}3GB") shouldBe 14.3
        stripSizeUnit("1${ds}6GB") shouldBe 1.6

        stripSizeUnit("14 МБ") shouldBe 14.0
        stripSizeUnit("14МБ") shouldBe 14.0
        stripSizeUnit("14${ds}3МБ") shouldBe 14.3
        stripSizeUnit("1${ds}6МБ") shouldBe 1.6
    }
}