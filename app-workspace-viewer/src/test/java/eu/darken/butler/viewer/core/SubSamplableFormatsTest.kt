package eu.darken.butler.viewer.core

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Telephoto accepts far more than BitmapRegionDecoder can tile, so this whitelist is what keeps a
 * valid BMP or an API 26/27 HEIF out of the tile decoder and on the Coil painter fallback.
 */
@RunWith(RobolectricTestRunner::class)
class SubSamplableFormatsTest {

    @Test
    @Config(sdk = [34])
    fun `the region decoder formats are accepted`() {
        SubSamplableFormats.supports("image/jpeg") shouldBe true
        SubSamplableFormats.supports("image/png") shouldBe true
        SubSamplableFormats.supports("image/webp") shouldBe true
    }

    @Test
    @Config(sdk = [34])
    fun `formats without region support are rejected`() {
        SubSamplableFormats.supports("image/bmp") shouldBe false
        SubSamplableFormats.supports("image/gif") shouldBe false
        SubSamplableFormats.supports("image/avif") shouldBe false
        SubSamplableFormats.supports("image/svg+xml") shouldBe false
        SubSamplableFormats.supports("application/octet-stream") shouldBe false
        SubSamplableFormats.supports(null) shouldBe false
    }

    @Test
    @Config(sdk = [28])
    fun `heif is accepted from api 28`() {
        SubSamplableFormats.supports("image/heif") shouldBe true
        SubSamplableFormats.supports("image/heic") shouldBe true
    }

    @Test
    @Config(sdk = [26])
    fun `heif is rejected below api 28`() {
        SubSamplableFormats.supports("image/heif") shouldBe false
        SubSamplableFormats.supports("image/heic") shouldBe false
    }
}
