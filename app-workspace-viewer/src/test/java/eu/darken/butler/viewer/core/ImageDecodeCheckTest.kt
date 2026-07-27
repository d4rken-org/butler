package eu.darken.butler.viewer.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The check exists to answer a yes/no question about a file, and it may never cost more than a
 * thumbnail to ask it - a header claiming absurd dimensions must not be able to push the process
 * out of memory before telephoto's bounded tile decoder ever sees the file.
 *
 * Robolectric stubs the decoding itself, so what is asserted here is the size arithmetic that
 * decides how much may be allocated, and that every way the decode can fail resolves to a pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageDecodeCheckTest {

    private fun sampledEdge(edge: Int, sample: Int) = edge / sample + if (edge % sample == 0) 0 else 1

    @Test
    fun `a small image is read at full size`() {
        ImageDecodeCheck.sampleSizeFor(100, 80) shouldBe 1
        ImageDecodeCheck.sampleSizeFor(256, 256) shouldBe 1
    }

    @Test
    fun `a photo is sampled down to the budget`() {
        // 12MP, the size the check was built around: 4000/16 = 250px, well inside the 256px cap.
        ImageDecodeCheck.sampleSizeFor(4000, 3000) shouldBe 16
    }

    @Test
    fun `the largest edge decides, not the total`() {
        // A panorama is mostly one long edge, and that edge is what the allocation follows.
        ImageDecodeCheck.sampleSizeFor(8000, 500) shouldBe 32
        ImageDecodeCheck.sampleSizeFor(500, 8000) shouldBe 32
    }

    @Test
    fun `an extreme header is sampled far harder than any fixed ratio`() {
        // The largest JPEG that can be declared. A fixed 16x would have targeted ~4096x4096, some
        // 67MB of software bitmap, before a single tile was ever decoded.
        val sample = ImageDecodeCheck.sampleSizeFor(65_535, 65_535)!!

        sample shouldBe 256
        sampledEdge(65_535, sample) shouldBeLessThanOrEqual ImageDecodeCheck.MAX_EDGE
    }

    @Test
    fun `no declared size can push the allocation past the budget`() {
        val declared = listOf(1, 17, 255, 257, 1024, 4000, 12_000, 65_535, 100_000, 262_144)

        declared.forEach { edge ->
            val sample = ImageDecodeCheck.sampleSizeFor(edge, edge)!!
            sampledEdge(edge, sample) shouldBeLessThanOrEqual ImageDecodeCheck.MAX_EDGE
        }
    }

    @Test
    fun `a header past what sampling can bound is skipped, not decoded`() {
        // Nothing legitimate declares this. Passing on it costs a missed check; decoding it costs
        // the process, so the check steps aside.
        ImageDecodeCheck.sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE).shouldBeNull()
        ImageDecodeCheck.sampleSizeFor(ImageDecodeCheck.MAX_SAMPLE_SIZE * ImageDecodeCheck.MAX_EDGE + 1, 4)
            .shouldBeNull()
    }

    @Test
    fun `a header without usable dimensions is skipped`() {
        ImageDecodeCheck.sampleSizeFor(0, 100).shouldBeNull()
        ImageDecodeCheck.sampleSizeFor(100, 0).shouldBeNull()
        ImageDecodeCheck.sampleSizeFor(-1, -1).shouldBeNull()
    }

    @Test
    fun `running out of memory says nothing about the file`() {
        // OutOfMemoryError is an Error, so it would escape a catch on Exception entirely - and a
        // failed allocation is never evidence that an image is damaged.
        ImageDecodeCheck.classify(OutOfMemoryError("no room")) shouldBe ImageDecodeCheck.Verdict.UNKNOWN
    }

    @Test
    fun `a header we refuse to allocate for is inconclusive, never a defect`() {
        val refused = ImageDecodeCheck.UncheckableImage("2147483647x2147483647")

        ImageDecodeCheck.classify(refused) shouldBe ImageDecodeCheck.Verdict.UNKNOWN
    }

    @Test
    fun `anything else the decode throws leaves the file alone`() {
        ImageDecodeCheck.classify(IllegalStateException("no decoder")) shouldBe ImageDecodeCheck.Verdict.UNKNOWN
    }

    @Test
    fun `cancellation is passed on, not turned into a verdict`() {
        shouldThrow<CancellationException> { ImageDecodeCheck.classify(CancellationException("cancelled")) }
    }
}
